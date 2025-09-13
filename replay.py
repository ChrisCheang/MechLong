import json 
from vpython import *
from typing import Dict, List
import numpy as np
import time

import asyncio
import threading

from pyquaternion import Quaternion

from sklearn.linear_model import LinearRegression

import keyboard



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

tx = 1.525
ty = 2.73

c1l = [-1.5, 0, 1.09-0.76]#[0, -0.87, 0.29]
c2l = [-1.65, ty, 1.09-0.76]#[-0.62, 0, 0.1]

c1t = [tx/2,ty/2,0]  #[0,0,0]
c2t = [tx/2,ty/2,0]   #[0,0,0]

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
gc3 = None
vp_lock = threading.Lock()


def setup_vpython_vis():
    global tx, ty, ball, ball2, g1, gc, gc2, gc3, camera_1_location, camera_1_direction, camera_2_location

    scene.width = 768#640  
    scene.height = 576#480
    scene.title = "40+ Tracking"
    scene.background = vec(0.7,0.7,0.7)

    g1 = graph(xtitle='time(s)',ytitle='speed(m/s)',xmin=0,ymin=0,ymax=20,align='left')
    gc = gcurve(color=color.orange) #filtered
    gc2 = gcurve(color=color.blue) #unfiltered

    box(pos=vector(tx/2, -0.005, -ty/2), size=vector(tx, 0.01, ty), color=vec(0.7,0.7,1)) # table 2.74, 1.525, 0.05
    box(pos=vector(tx/2, 0.1525/2, -ty/2), size=vector(tx, 0.1525, 0.001), color=vec(1,1,1))


    #initialize ball (filtered)
    ball = sphere(pos=vector(0, 0, 0), radius=0.021, color=color.orange, make_trail=True, retain=100)
    ball.trail_color = color.orange
    ball.trail_radius = 0.005

    #initialize ball 2 (unfiltered)
    ball2 = sphere(pos=vector(0, 0, 0), radius=0.021, color=color.orange, make_trail=True, retain=100)
    ball2.trail_color = color.blue
    ball2.trail_radius = 0.005

    gc3 = curve(color=color.black) #ball trajectory estimation




def update_vpython_vis(intersection_point, index, ball, curve, data, re=False):

    if intersection_point == (0, 0, 0):
        return  # Skip invalid points
    
    with vp_lock:
        #try:
        x, y, z = intersection_point
        ball.pos = vector(x,z,-y)

        #for i in range(len(ball_data)):
        times = [dic['time'] for dic in data]
        if re:
            speeds = [dic['speed'][3] for dic in data]
        else:
            speeds = [dic['speed'] for dic in data]
        curve.plot(times[index]/1000,speeds[index])
        if data[index]['time'] > 1000:
            g1.xmin = data[index]['time']/1000 - 5
        
        #except Exception as e:
            #logger.error(f"Error updating VPython visualization: {e}")

def update_vpython_traj_projection(p,v):
    global gc3

    ts = np.linspace(0,1,10)

    # clear old curve points
    gc3.clear()

    # draw new points
    traj = Bounce(p,v)
    for t in range(len(ts)):
        point = traj.traj(ts[t])
        gc3.append(pos=vector(point[0],point[2],-point[1]))



class Bounce:
    def __init__(self,p,v):
        self.p = p
        self.v = v

    def traj_single(self, t):
        x = self.p[0]+t*self.v[0]
        y = self.p[1]+t*self.v[1]
        z = self.p[2]+t*self.v[2]-0.5*9.81*t**2
        return [x,y,z]

    def firstbouncet(self):
        discrim = (self.v[2])**2+4*0.5*9.81*self.p[2]
        if discrim < 0:
            return 0
        else:
            numer = self.v[2]+sqrt(discrim)
            denom = 2*0.5*9.81
            return numer/denom
    
    def pfirstbounce(self):
        return self.traj_single(self.firstbouncet())
    
    def traj_second(self,t):
        eball = 0.949 # empirical estimation of ball coeff of restitution
        Bounce2 = Bounce(self.pfirstbounce(),[self.v[0],self.v[1],eball**2 * abs(self.v[2]-9.81*self.firstbouncet())])
        return Bounce2.traj_single(t)
    
    def traj(self,t):
        if t < self.firstbouncet():
            return self.traj_single(t)
        else:
            return self.traj_second(t-self.firstbouncet())
        


# Read files. note: check if both files are the same length
with open('data_2025-09-07 16-11-25.json1', 'r') as file:
    ball_data = list(map(json.loads, file))
with open('data_unfiltered_2025-09-07 16-11-25.json1', 'r') as file:
    ball_data_unfiltered = list(map(json.loads, file))


# new dataset to test other filters
no_points = 4
#ball_data_re = ball_data_unfiltered[:(no_points+1)] #initialise as first five unfiltered datapoints, in main would be five unknown ones.

ball_data_re = [{'intersection': [0,0,0], 'time': 0, 'speed': [0,0,0,0]},  #note that the fourth number of speed is the magnitude for more convenience
                {'intersection': [0,0,0], 'time': 10, 'speed': [0,0,0,0]},
                {'intersection': [0,0,0], 'time': 20, 'speed': [0,0,0,0]},
                {'intersection': [0,0,0], 'time': 30, 'speed': [0,0,0,0]},
                {'intersection': [0,0,0], 'time': 40, 'speed': [0,0,0,0]}]

def linear_regression_filter(xdata,ydata,x_extrapolate):
    x = np.array(xdata).reshape((-1,1))
    y = np.array(ydata)
    model = LinearRegression().fit(x,y)
    return model.predict(np.array(x_extrapolate).reshape((-1,1)))[0]

for i in range(no_points+1,len(ball_data_unfiltered)-1):
    times = [dic['time'] for dic in ball_data_unfiltered[(i-no_points+1):(i+1)]]
    xs = [dic['intersection'][0] for dic in ball_data_unfiltered[(i-no_points+1):(i+1)]]
    ys = [dic['intersection'][1] for dic in ball_data_unfiltered[(i-no_points+1):(i+1)]]
    zs = [dic['intersection'][2] for dic in ball_data_unfiltered[(i-no_points+1):(i+1)]]

    #linear regression filter
    #x = linear_regression_filter(times, xs, ball_data_unfiltered[i+1]['time'])
    #y = linear_regression_filter(times, ys, ball_data_unfiltered[i+1]['time'])
    #z = linear_regression_filter(times, zs, ball_data_unfiltered[i+1]['time'])

    dt = ball_data_unfiltered[i]['time'] - ball_data_unfiltered[i-1]['time']
    dt4 = ball_data_unfiltered[i]['time'] - ball_data_unfiltered[i-4]['time']

    #first order filter
    x = filter(xs[-1],ball_data_re[-1]['intersection'][0],0.1,dt) #xs[-1]
    y = filter(ys[-1],ball_data_re[-1]['intersection'][1],0.02,dt) #ys[-1]
    z = filter(zs[-1],ball_data_re[-1]['intersection'][2],0.05,dt) #zs[-1]

    speed = [0,0,0,0]

    for coordinate in [0,1,2]:
        speed[coordinate] = ([x,y,z][coordinate]-ball_data_re[-4]['intersection'][coordinate])/(dt4/1000)
        speed[coordinate] = filter(speed[coordinate],ball_data_re[-1]['speed'][coordinate],0.05,dt)

    speed[3] = dist([x,y,z],ball_data_re[-4]['intersection'])/(dt4/1000)
    speed[3] = filter(speed[3],ball_data_re[-1]['speed'][3],0.05,dt)

    #x-jump filter (don't add new point if x jumps by more than a threshold difference (to remove large jumps from possible erronous detection), makes the filtered and unfiltered lists different in length so beware)
    x_jump = abs(x-ball_data_re[-1]['intersection'][0])
    if x_jump < 1000:
        new_data = {'intersection': [x,y,z], 
                    'time': ball_data_unfiltered[i]['time'],
                    'speed': speed}   
        ball_data_re.append(new_data)


    print("calculating")
print("done")



start_time = int(time.time()*1000)

setup_vpython_vis()

i_start = 40000 #2500 for 09-04#2, 30000 for 09-04#1, 11500 for 09-02
i_end = len(ball_data) #note: this cuts visualisation immediately, to pause use ctrl c in terminal
i = i_start # start index
fast_forward = False

pause = False
now_time = ball_data_re[i_start]['time']
play_time = now_time
pause_time = now_time

while i < i_end:
    
    if keyboard.is_pressed('p'):
        if not pause:
            time_paused = int(time.time()*1000)
        pause = True
        
    elif keyboard.is_pressed('r'):
        if pause:
            pause_time = int(time.time()*1000)-time_paused
            start_time += pause_time
        pause = False

    if not pause:
        now_time = int(time.time()*1000)-start_time+ball_data_re[i_start]['time']
        #now_time += (int(time.time()*1000)-start_time)+now_time

        if not fast_forward:
            if ball_data_re[i]['time'] < now_time:
                update_vpython_vis(ball_data_re[i]['intersection'], index=i, data=ball_data_re, ball=ball, curve=gc, re=True)
                #update_vpython_vis(ball_data_unfiltered[i]['intersection'], index=i, ball=ball2, curve=gc2, data=ball_data_unfiltered) #times between the two sets match
                update_vpython_traj_projection(ball_data_re[i]['intersection'],ball_data_re[i]['speed'])
                i += 1
        else:
            update_vpython_vis(ball_data_re[i]['intersection'], index=i, data=ball_data_re, ball=ball, curve=gc, re=True)
            #update_vpython_vis(ball_data_unfiltered[i]['intersection'], index=i, ball=ball2, curve=gc2, data=ball_data_unfiltered) #times between the two sets match
            update_vpython_traj_projection(ball_data_re[i]['intersection'],ball_data_re[i]['speed'])
            i += 5
        #start_time += int(time.time()*1000)-(now_time+start_time)
        #start_time = int(time.time()*1000) - pause_time
    #print(now_time)
    print(f"start: {round(start_time,2)}, now: {round(now_time/1000,2)}, record time: {round(ball_data_re[i]['time']/1000,2)}, index = {i}, paused = {pause}, speed = ({round(ball_data_re[i]['speed'][0],2)},{round(ball_data_re[i]['speed'][1],2)},{round(ball_data_re[i]['speed'][2],2)})")










