from math import *

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
        

# idea: Ballreader object created at the start of program, with state "not in play" (out of "not in play", "in play")
# at each iteration, the state is updated based on trajectory estimation with the Bounce class with similar code from replay.py
# if a ball is detected to travel from one side to the other with sufficient velocity, state changes to "in play" and the Ballreader object starts collecting ball pos data stream
# "in play" has two/three types - "top spin", "back spin" and "side spin"?. With 2+ points, actual trajectory is compared with prediction from the Bounce object
# if ball drops more, identify as "top spin", "back spin" if the opposite and "side spin" if x varies from trajectory a lot
# Could be easier to incorporate the above functionality straight into ball_data 

tx = 1.525
ty = 2.73

class Ballreader:
    global ty

    def __init__(self, data, n=4, direction=1):
        self.n = n # no. of points to countback for calculations
        self.data = data[:-n] # when initializing, last n points are inserted as 
        self.type = "none" # "none" at init, then "else", "to recieve - after bounce" and "to recieve - before bounce" (i.e. flying towards machine)
        self.spin = "none" # "none" then "top", "back"

        # p0 v0 and t0 are pos velocity and record time (ms) of the point n steps back
        p0 = data[-n]['intersection']
        v0 = data[-n]['speed']
        self.t0 = data[-n]['time']
        self.reftraj = Bounce(p0,v0) # reference trajectory to compare to
        self.bounce_lock = False

        self.direction = direction #determines side on which machine is located. 1 or -1, 1 is default i.e. on origin side


    def typeset(self, p, v):
        ycounter = 0
        for i in range(len(self.data)):
            if self.direction*self.data[i]['speed'][1] < 0:
                ycounter += 1  # if the condition above is true for all 5 points (i.e. neg. y velocity for all five points, i.e. travelling to play area, ycounter = n)
        if self.direction*v[1] < 0:
            ycounter += 1
        if self.direction*v[1] < 0 and self.direction*Bounce(p,v).pfirstbounce()[1] < self.direction*ty:
        #if ycounter == self.n - 1 and Bounce(p,v).pfirstbounce()[1] < ty:
            if self.data[-1]['speed'][2] < 0 and v[2] > 0: # bounce - changing from negative in z to positive in z
                self.type = "to recieve - after bounce"
                self.bounce_lock = True  # locks self.type to after bounce after bounce until ball is returned
            elif self.bounce_lock == False:
                self.type = "to recieve - before bounce"
        else:
            self.type = "returned"
            self.bounce_lock = False


    def spinset(self, p, t):
        pred_point = self.reftraj.traj((t-self.t0)/1000)
        if self.type != "returned":
            if pred_point[2] < p[2]:
                self.spin = "back"
            else: 
                self.spin = "top"
        else:
            self.spin = "none"
    
        
    def update(self, data, p, v, t):  # called every new point after it has been appended to data, so attributes to the ballreader object can be compared
        self.data = data[:-self.n]
        orig_state = self.type
        self.typeset(p, v)
        if orig_state == "returned" and self.type != "returned": # reference trajectory is updated when a ball is first detected to be returned from the opponent (i.e. first instance a ball is detected to need recieving)
            p0 = data[-self.n]['intersection']
            v0 = data[-self.n]['speed']
            self.reftraj = Bounce(p0,v0)
        self.spinset(p, t)
