# ball_tracker_server.py
import asyncio
import websockets
import json
from datetime import datetime
import logging
import threading
import time
from typing import Dict, List

from skspatial.objects import Line


# Set up logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("BallTrackerServer")

connected_clients = set()
received_data: List[Dict] = []
data_lock = threading.Lock()

# Live updating variables

global line_1
line_1 = Line(point=[0, -0.9, 0.25], direction=[0.0, 0.964, -0.267])

global line_2
line_2 = Line(point=[-0.62, 0, 0.15], direction=[-0.972, 0.0, -0.235])


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
                    if source == 1:
                        line_1 = Line(point=[0, -0.9, 0.25], direction=[x, y, z])
                    if source == 2:
                        line_2 = Line(point=[-0.62, 0, 0.15], direction=[x, y, z])
                    logger.info(f"Ball position - X: {x:.4f}, Y: {y:.4f}, Z: {z:.4f}, camera: {source}")
                
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
        time.sleep(30)  # Print stats every 10 seconds
        
        with data_lock:
            if received_data:
                latest_data = received_data[-1]
                ball_axes = latest_data.get('ballAxes', {})
                x, y, z = ball_axes.get('x', 0), ball_axes.get('y', 0), ball_axes.get('z', 0)
                
                print("\n" + "-"*60)
                print("CURRENT BALL POSITION STATISTICS")
                print("-"*60)
                print(f"Latest position: X={x:.4f}, Y={y:.4f}, Z={z:.4f}")
                print(f"Total messages received: {len(received_data)}")
                print(f"Active clients: {len(connected_clients)}")
                print(f"Last update: {datetime.now().strftime('%H:%M:%S')}")
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

    print(line_1)
    print(line_2)
    
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
