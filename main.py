# ball_tracker_server.py
import asyncio
import websockets
import json
from datetime import datetime
import logging
import threading
import time
from typing import Dict, List



import numpy as np

import concurrent.futures

# Add thread pool executor for CPU-bound calculations
executor = concurrent.futures.ThreadPoolExecutor(max_workers=2)

# Set up logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("BallTrackerServer")

connected_clients = set()
received_data: List[Dict] = []
data_lock = threading.Lock()



# line class for skew line approximate intersection

class Line:

    def __init__(self, point, direction):
        self.point = point # input as [x,y,z]
        self.direction = direction # input as normalised [x,y,z]


    def skew_int(self, B):

        """Optimized version using numpy instead of SymPy"""
        if self.direction[2] == 1 or B.direction[2] == 1:
            return (0, 0, 0)
        
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
            return tuple(intersection)
        except np.linalg.LinAlgError:
            return (0, 0, 0)



# Live updating variables with threading log for multithreading (for multitasking)

line_1 = Line(point=[0, -0.9, 0.25], direction=[0.0, 0.964, -0.267])
line_2 = Line(point=[-0.62, 0, 0.15], direction=[0.972, 0.0, -0.235])
line_lock = threading.Lock()  # Lock for thread-safe access to lines


def update_line(source: int, direction: List[float]):
    """Update line_1 or line_2 with thread safety"""
    global line_1, line_2
    
    with line_lock:
        if source == 1:
            line_1 = Line(point=[0, -0.9, 0.25], direction=direction)
            #logger.info(f"Updated line_1 with direction: {direction}")
        elif source == 2:
            line_2 = Line(point=[-0.62, 0, 0.15], direction=direction)
            #logger.info(f"Updated line_2 with direction: {direction}")

def get_lines():
    """Get current line_1 and line_2 with thread safety"""
    global line_1, line_2
    
    with line_lock:
        return line_1, line_2
    

def calculate_intersection():
    line1, line2 = get_lines()
    
    try:
        # Calculate intersection point
        intersection = line1.skew_int(line2)
        #logger.info(f"Intersection point: {intersection}")
        return intersection
    except Exception as e:
        logger.error(f"Error calculating intersection: {e}")
        return None
    

async def calculate_intersection_async():
    """Async wrapper for the intersection calculation"""
    loop = asyncio.get_event_loop()
    try:
        # Run the CPU-intensive calculation in a thread pool
        intersection = await loop.run_in_executor(
            executor, 
            calculate_intersection
        )
        return intersection
    except Exception as e:
        logger.error(f"Error in async intersection calculation: {e}")
        return None


async def handle_connection(websocket, path):
    connected_clients.add(websocket)
    client_ip = websocket.remote_address[0]
    logger.info(f"New client connected from {client_ip}. Total clients: {len(connected_clients)}")
    
    try:
        async for message in websocket:
            try:
                # Parse JSON message
                data = json.loads(message)
                
                # Add reception timestamp
                data['received_at'] = datetime.now().isoformat()
                data['client_ip'] = client_ip
                
                # Store the data for printing
                with data_lock:
                    received_data.append(data)
                
                # Log the received data
                #logger.info(f"Received ball data: {json.dumps(data, indent=2)}")
                
                # Process the ball axes data
                ball_axes = data.get('ballAxes', {})
                if ball_axes:
                    x, y, z = ball_axes.get('x', 0), ball_axes.get('y', 0), ball_axes.get('z', 0)
                    source = data.get('source', 'unknown')
                    if source in [1, 2]:
                        update_line(source, [x, y, z])
                    #logger.info(f"Ball position - X: {x:.4f}, Y: {y:.4f}, Z: {z:.4f}, camera: {source}")

                intersection = await calculate_intersection_async()
                if intersection is not None:
                    logger.info(f"viewVec - X: {x:.2f}, Y: {y:.2f}, Z: {z:.2f}, camera: {source}, current intersection: {intersection}")
                
                # Save to file for later analysis
                #with open('ball_tracking_data.jsonl', 'a') as f:
                    #f.write(json.dumps(data) + '\n')
                
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
