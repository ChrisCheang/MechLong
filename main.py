# ball_tracker_server.py
import matplotlib.pyplot as plt

import asyncio
import websockets
import json
from datetime import datetime
import logging
import threading
import time
from typing import Dict, List

from pyquaternion import Quaternion


import numpy as np

import concurrent.futures

from vpython import *


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



# Add thread pool executor for CPU-bound calculations
executor = concurrent.futures.ThreadPoolExecutor(max_workers=2)

# Set up logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("BallTrackerServer")

connected_clients = set()
received_data: List[Dict] = []
data_lock = threading.Lock()

start_time = int(time.time()*1000)
start_datetime = datetime.now().strftime("%Y-%m-%d %H-%M-%S")

ball_data = [{'intersection': [0,0,0], 'time': 0, 'speed': 0},
             {'intersection': [0,0,0], 'time': 50, 'speed': 0}]

ball_data_unfiltered = [{'intersection': [0,0,0], 'time': 0, 'speed': 0},
                        {'intersection': [0,0,0], 'time': 50, 'speed': 0}]


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
line_lock = threading.Lock()  # Lock for thread-safe access to lines

# VPython vis setup
ball = None
cam1_line = cylinder(pos=vector(c1l[0],c1l[2],-c1l[1]), radius=0.005, color=color.blue)
cam2_line = cylinder(pos=vector(c2l[0],c2l[2],-c2l[1]), radius=0.005, color=color.red)
trail_points = []
max_trail_length = 50 
g1 = None
gc = None
vp_lock = threading.Lock()


def setup_vpython_vis():
    global ball, g1, gc, camera_1_location, camera_1_direction, camera_2_location

    scene.width = 640  
    scene.height = 480
    scene.title = "40+ Tracking"
    #scene.background = vec(0.3,0.3,0.3)

    g1 = graph(xtitle='time(s)',ytitle='speed(m/s)',xmin=0,ymin=0,ymax=10,align='left')
    gc = gcurve()

    tx = 0.85
    ty = 1.3

    box(pos=vector(tx/2, -0.005, -ty/2), size=vector(tx, 0.01, ty), color=vec(0.7,0.7,1)) # table 2.74, 1.525, 0.05

    #initialize ball
    ball = sphere(pos=vector(0, 0, 0), radius=0.021, color=color.orange, make_trail=True, retain=100)
    ball.trail_color = color.orange
    ball.trail_radius = 0.002
    logger.info("VPython visualization initialized")

    # Initialize camera view lines
    cam1_line.axis = vector(c1d[0],c1d[2],-c1d[1])
    cam2_line.axis = vector(c2d[0],c2d[2],-c2d[1])
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             

def update_vpython_vis(intersection_point, line1, line2):
    global ball, cam1_line, cam2_line, ball_data#, graph

    if intersection_point == (0, 0, 0):
        return  # Skip invalid points
    
    with vp_lock:
        #try:
        x, y, z = intersection_point
        ball.pos = vector(x,z,-y)

        mag1 = dist(line1.direction,[0,0,0])
        dist1 = dist(c1l,[x,y,z])

        mag2 = dist(line2.direction,[0,0,0])
        dist2 = dist(c2l,[x,y,z])


        cam1_line.axis = vector((dist1/mag1)*line1.direction[0],(dist1/mag1)*line1.direction[2],-(dist1/mag1)*line1.direction[1])
        cam2_line.axis = vector((dist2/mag2)*line2.direction[0],(dist2/mag2)*line2.direction[2],-(dist2/mag2)*line2.direction[1])

        #for i in range(len(ball_data)):
        times = [dic['time'] for dic in ball_data]
        speeds = [dic['speed'] for dic in ball_data]
        gc.plot(times[-1]/1000,speeds[-1])
        if ball_data[-1]['time'] > 1000:
            g1.xmin = ball_data[-1]['time']/1000 - 5
        
        #except Exception as e:
            #logger.error(f"Error updating VPython visualization: {e}")




def update_line(source: int, direction: List[float]):
    """Update line_1 or line_2 with thread safety"""
    global line_1, line_2, c1l, c2l
    
    with line_lock:
        if source == 1:
            line_1 = Line(point=c1l, direction=direction)
            #line_1.direction = direction
            #logger.info(f"1 Dir: {direction}")
        elif source == 2:
            line_2 = Line(point=c2l, direction=direction)
            #logger.info(f"2 Dir: {direction}")
            #line_1.direction = direction

def get_lines():
    """Get current line_1 and line_2 with thread safety"""
    global line_1, line_2
    
    with line_lock:
        return line_1, line_2
    

def calculate_intersection(): # can do plots and other calculations here?
    line1, line2 = get_lines()
    global ball_data
    
    try:
        intersection = line1.skew_int(line2)
        return intersection
    except Exception as e:
        logger.error(f"Error calculating intersection: {e}")
        return None
    

async def calculate_intersection_async(): # can do plots and other calculations here?
    """Async wrapper for the intersection calculation"""
    loop = asyncio.get_event_loop()

    try:
        # Run the CPU-intensive calculation in a thread pool
        intersection = await loop.run_in_executor(
            executor, 
            calculate_intersection
        )

        # update visualization for valid points
        if intersection is not None and intersection != [0,0,0]:
            line1, line2 = get_lines()
            await loop.run_in_executor(executor, update_vpython_vis, intersection, line1, line2)
        return intersection
    
    except Exception as e:
        logger.error(f"Error in async intersection calculation: {e}")
        return None


async def handle_connection(websocket, path):
    connected_clients.add(websocket)
    client_ip = websocket.remote_address[0]
    logger.info(f"New client connected from {client_ip}. Total clients: {len(connected_clients)}")
    global start_time, start_datetime, ball_data, ball_data_unfiltered

    try:
        async for message in websocket:
            try:
                # Parse JSON message
                data = json.loads(message)
                
                # Add reception timestamp
                data['received_at'] = datetime.now().isoformat()
                data['client_ip'] = client_ip
                
                # Log the received data
                #logger.info(f"Received ball data: {json.dumps(data, indent=2)}")
                
                # Process the ball axes data
                ball_axes = data.get('ballAxes', {})
                detected = data.get('detected', 'unknown')
                if detected == 1: # if a ball is detected update view lines
                    nx, ny = ball_axes.get('nx', 0), ball_axes.get('ny', 0)
                    source = data.get('source', 'unknown')
                    if source in [1, 2]:
                        direction = rotateVec(angles[source-1][0], angles[source-1][1], viewvec=[1,-nx,ny])
                        update_line(source, direction)
                    #logger.info(f"Ball position - X: {x:.4f}, Y: {y:.4f}, Z: {z:.4f}, camera: {source}")

                intersection = await calculate_intersection_async()

                if intersection is not None:
                    int_at_time = int(time.time()*1000) - start_time#-data.get('timestamp', 0)
                    dt = int_at_time - ball_data[-1]['time']
                    dt2 = int_at_time - ball_data[-2]['time']
                    speed = 0
                    if dt != 0 and dt2 != 0:
                        speed = dist(intersection,ball_data[-2]['intersection'])/(dt2/1000)

                        new_data = {'intersection': intersection, 
                                        'time': int_at_time,
                                        'speed': speed}
                        ball_data_unfiltered.append(new_data)

                        # Save raw data to file for later analysis
                        file_name = f"data_unfiltered_{start_datetime}.json1"
                        with open(file_name, 'a') as f:
                            f.write(json.dumps(new_data) + '\n')

                        # filter positions 
                        intersection[0] = filter(intersection[0],ball_data[-1]["intersection"][0],0.5,dt)
                        for i in [1,2]:
                            intersection[i] = filter(intersection[i],ball_data[-1]["intersection"][i],0.05,dt)
                        # speed first order filter with 0.05s tc
                        speed = filter(speed,ball_data[-1]['speed'],0.05,dt)

                        new_data = {'intersection': intersection, 
                                        'time': int_at_time,
                                        'speed': speed}
                        ball_data.append(new_data)

                        # Save filtered data to file for later analysis
                        file_name = f"data_{start_datetime}.json1"
                        with open(file_name, 'a') as f:
                            f.write(json.dumps(new_data) + '\n')
                    
                    logger.info(f"{round(speed,1)} m/s, ({round(intersection[0],3)}, {round(intersection[1],3)}, {round(intersection[2],3)})")
                
                                
                # Store the data for printing
                #with data_lock:
                #    received_data.append(data)

                
                # Send acknowledgment back if needed
                response = {
                    'status': 'received',
                    'received_at': data['received_at'],
                    'processed': True
                }
                
                await websocket.send(json.dumps(response))
                
            except json.JSONDecodeError as e:
                logger.error(f"Invalid JSON received: {message} - Error: {e}")
                error_response = {'error': 'Invalid JSON format'}
                await websocket.send(json.dumps(error_response))
                
    except websockets.exceptions.ConnectionClosed:
        logger.info(f"Client {client_ip} disconnected")
    finally:
        connected_clients.remove(websocket)


def print_data_statistics():
    """Thread function to print periodic statistics"""
    while True:
        time.sleep(30)  # Print stats every 30 seconds
        
        with data_lock:
            if received_data:
                latest_data = received_data[-1]
                ball_axes = latest_data.get('ballAxes', {})
                x, y, z = ball_axes.get('x', 0), ball_axes.get('y', 0), ball_axes.get('z', 0)
                
                # Get current lines
                current_line1, current_line2 = get_lines()
                
                print("\n" + "-"*60)
                print("CURRENT BALL POSITION STATISTICS")
                print("-"*80)
                print(f"Latest position: X={x:.4f}, Y={y:.4f}, Z={z:.4f}")
                print(f"Line 1 direction: {current_line1.direction}")
                print(f"Line 2 direction: {current_line2.direction}")
                print(f"Total messages received: {len(received_data)}")
                print(f"Active clients: {len(connected_clients)}")
                print(f"Last update: {datetime.now().strftime('%H:%M:%S')}")
                
                # Calculate and display intersection
                #intersection = calculate_intersection()
                #if intersection is not None:
                #    print(f"Intersection point: {intersection}")
                
                print("-"*60)

async def main():
    # Set up vpython vis
    setup_vpython_vis()

    # Start the data printing threads
    #print_thread = threading.Thread(target=print_received_data, daemon=True)
    stats_thread = threading.Thread(target=print_data_statistics, daemon=True)
    #print_thread.start()
    stats_thread.start()
    
    # Start WebSocket server
    server = await websockets.serve(handle_connection, "0.0.0.0", 8765)
    logger.info("WebSocket server started on ws://0.0.0.0:8765")
    
    # Display connection information
    print("\n" + "="*60)
    print("BALL TRACKING WEB SOCKET SERVER")
    print("="*60)
    print("Server: ws://0.0.0.0:8765")
    print("Waiting for Android client connections...")
    print("Press Ctrl+C to stop the server")
    print("="*60)
    
    # Keep server running
    await server.wait_closed()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer stopped by user")
        
        # Print final statistics
        with data_lock:
            print("\n" + "="*60)
            print("FINAL SERVER STATISTICS")
            print("="*60)
            print(f"Total messages received: {len(received_data)}")
            if received_data:
                first_msg = received_data[0]
                last_msg = received_data[-1]
                print(f"First message timestamp: {first_msg.get('timestamp', 'N/A')}")
                print(f"Last message timestamp: {last_msg.get('timestamp', 'N/A')}")
            print("="*60)
