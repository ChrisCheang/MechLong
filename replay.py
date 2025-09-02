import json 
from vpython import *
from typing import Dict, List
import numpy as np
import time

import asyncio
import threading

from pyquaternion import Quaternion


class Line:

    def __init__(self, point, direction):
        self.point = point # input as [x,y,z]
        self.direction = direction # input as normalised [x,y,z]

    def skew_int(self, B):
        
        # Convert to numpy arrays for faster computation
        p1 = np.array(self.point)
        d1 = np.array(self.direction)
        p2 = np.array(B.point)
        d2 = np.array(B.direction)
        
        # Solve for t and s using linear algebra
        # The equations are: (p2 + s*d2 - p1 - t*d1) · d1 = 0
        #                   (p2 + s*d2 - p1 - t*d1) · d2 = 0
        
        A = np.array([
            [np.dot(d1, d1), -np.dot(d1, d2)],
            [np.dot(d1, d2), -np.dot(d2, d2)]
        ])
        
        b = np.array([
            np.dot(p2 - p1, d1),
            np.dot(p2 - p1, d2)
        ])
        
        try:
            t, s = np.linalg.solve(A, b)
            point1 = p1 + t * d1
            point2 = p2 + s * d2
            intersection = 0.5 * (point1 + point2)
            if abs(intersection[0]+intersection[1]+intersection[2]) > 10:
                return [0,0,0] #calculated intersection is wonky on startup, this saturates output so view is manageable
            else:
                return list(intersection)

        except np.linalg.LinAlgError:
            return [0, 0, 0]
        
    def direction_magnitude(self):
        return dist(self.direction,[0,0,0])



# line class for skew line approximate intersection
# Live updating variables with threading log for multithreading (for multitasking)

def rotateVec(s, u, viewvec=[1,0,0]):
    orig = Quaternion(0,viewvec[0],viewvec[1],viewvec[2])
    pitchQ = Quaternion(axis=(0,1,0), radians=u)
    rollQ = Quaternion(axis=(0,0,1), radians=-s)
    d = rollQ.rotate(pitchQ.rotate(orig))
    return [d.x,d.y,d.z]

def dist(p1, p2):
    return sqrt((p1[0]-p2[0])**2+(p1[1]-p2[1])**2+(p1[2]-p2[2])**2)

def filter(unfiltered,lastval,tc,dt):
    # input is unfiltered expected output, output is 
    return (tc*(1000/dt)*lastval+unfiltered)/(tc*(1000/dt)+1)


# camera locations and directions (l and d), use desmos model as reference
# Camera 1

c1l = [1.14+0.85, 1.3, 1.09-0.76]#[0, -0.87, 0.29]
c2l = [1.93+0.85, 0, 1.08-0.76]#[-0.62, 0, 0.1]

c1t = [0,0,0]  #[0,0,0]
c2t = [0,0,0]   #[0,0,0]

s1 = atan2(c1l[1]-c1t[1],c1t[0]-c1l[0])
s2 = atan2(c2l[1]-c2t[1],c2t[0]-c2l[0])

u1 = atan2(c1l[2]-c1t[2],sqrt((c1l[0]-c1t[0])**2+(c1l[1]-c1t[1])**2))
u2 = atan2(c2l[2]-c2t[2],sqrt((c2l[0]-c2t[0])**2+(c2l[1]-c2t[1])**2))

angles = np.array([[s1,u1],[s2,u2]]) # directions defined by s, u roll and pitch as per desmos convention, [[s1,u1],[s2,u2]]

c1d = rotateVec(angles[0][0], angles[0][1])
c2d = rotateVec(angles[1][0], angles[1][1])

line_1 = Line(point=c1l, direction=c2d)
line_2 = Line(point=c2l, direction=c2d)

# VPython vis setup
ball = None
ball2 = None
g1 = None
gc = None
gc2 = None
vp_lock = threading.Lock()


def setup_vpython_vis():
    global ball, ball2, g1, gc, gc2, camera_1_location, camera_1_direction, camera_2_location

    scene.width = 640  
    scene.height = 480
    scene.title = "40+ Tracking"
    #scene.background = vec(0.3,0.3,0.3)

    g1 = graph(xtitle='time(s)',ytitle='speed(m/s)',xmin=0,ymin=0,ymax=10,align='left')
    gc = gcurve()
    gc2 = gcurve()

    tx = 0.85
    ty = 1.3

    box(pos=vector(tx/2, -0.005, -ty/2), size=vector(tx, 0.01, ty), color=vec(0.7,0.7,1)) # table 2.74, 1.525, 0.05

    #initialize ball
    ball = sphere(pos=vector(0, 0, 0), radius=0.021, color=color.orange, make_trail=True, retain=100)
    ball.trail_color = color.orange
    ball.trail_radius = 0.002

    #initialize ball 2
    ball2 = sphere(pos=vector(0, 0, 0), radius=0.021, color=color.orange, make_trail=True, retain=100)
    ball2.trail_color = color.blue
    ball2.trail_radius = 0.002




def update_vpython_vis(intersection_point, index):
    global ball, gc, cam1_line, cam2_line, ball_data#, graph

    if intersection_point == (0, 0, 0):
        return  # Skip invalid points
    
    with vp_lock:
        #try:
        x, y, z = intersection_point
        ball.pos = vector(x,z,-y)

        #for i in range(len(ball_data)):
        times = [dic['time'] for dic in ball_data]
        speeds = [dic['speed'] for dic in ball_data]
        gc.plot(times[index]/1000,speeds[index])
        if ball_data[index]['time'] > 1000:
            g1.xmin = ball_data[index]['time']/1000 - 5
        
        #except Exception as e:
            #logger.error(f"Error updating VPython visualization: {e}")

def update_vpython_vis_unfiltered(intersection_point, index):
    global ball2, gc2, cam1_line, cam2_line, ball_data_unfiltered#, graph

    if intersection_point == (0, 0, 0):
        return  # Skip invalid points
    
    with vp_lock:
        #try:
        x, y, z = intersection_point
        ball2.pos = vector(x,z,-y)

        #for i in range(len(ball_data)):
        times = [dic['time'] for dic in ball_data_unfiltered]
        speeds = [dic['speed'] for dic in ball_data_unfiltered]
        gc2.plot(times[index]/1000,speeds[index])
        if ball_data_unfiltered[index]['time'] > 1000:
            g1.xmin = ball_data_unfiltered[index]['time']/1000 - 5
        
        #except Exception as e:
            #logger.error(f"Error updating VPython visualization: {e}")





# Read files. note: check if both files are the same length
with open('data_2025-09-02 16-37-23.json1', 'r') as file:
    ball_data = list(map(json.loads, file))
with open('data_unfiltered_2025-09-02 16-37-23.json1') as file:
    ball_data_unfiltered = list(map(json.loads, file))


start_time = int(time.time()*1000)

setup_vpython_vis()

i_start = 11500
i_end = 13000 #len(ball_data)
i = i_start # start index
while i < i_end:
    now_time = int(time.time()*1000)-start_time+ball_data[i_start]['time']
    if ball_data[i]['time'] < now_time:
        update_vpython_vis(ball_data[i]['intersection'], index=i)
        update_vpython_vis_unfiltered(ball_data_unfiltered[i]['intersection'], index=i) #times between the two sets match
        print(f"{(ball_data[i]['time'])/1000} sec")
        i += 1
    







