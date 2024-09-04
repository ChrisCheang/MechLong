from sympy import sqrt, tan, sin, cos
from sympy import solve
import sympy as smp
from sympy import *


v1x = Symbol('v1x')
v1y = Symbol('v1y')
v2x = Symbol('v2x')
v2y = Symbol('v2y')
v3x = Symbol('v3x')
a = Symbol('a')
b = Symbol('b')
c = Symbol('c')
s = Symbol('s')
u = Symbol('u')

tx = Symbol('tx')
ty = Symbol('ty')




def p1numer(x,y,z,a,b,c,s,u):
    return -(x-a)*sin(s)-(y-b)*cos(s)

def p2numer(x,y,z,a,b,c,s,u):
    inner = (x-a)*cos(s)-(y-b)*sin(s)
    return inner*sin(u) + (z-c)*cos(u)

def denom(x,y,z,a,b,c,s,u):
    inner = (x-a)*cos(s)-(y-b)*sin(s)
    return inner*cos(u) - (z-c)*sin(u)




exp1 = p1numer(0,0,0,a,b,c,s,u)/denom(0,0,0,a,b,c,s,u) - v1x
exp2 = p2numer(0,0,0,a,b,c,s,u)/denom(0,0,0,a,b,c,s,u) - v1y
exp3 = p1numer(tx,0,0,a,b,c,s,u)/denom(tx,0,0,a,b,c,s,u) - v2x
exp4 = p2numer(tx,0,0,a,b,c,s,u)/denom(tx,0,0,a,b,c,s,u) - v2y
exp5 = p1numer(0,ty,0,a,b,c,s,u)/denom(0,ty,0,a,b,c,s,u) - v3x

equations = [exp1,exp2,exp3,exp4,exp5]

#equations = [a**2 + tan(b) - 2*tx, b + 4*ty]

solutions = solve(equations, a, b, c, s, dict=True)

print(solutions)

print("done")


#equations = [tan(s)-(b-y)/(x-a), tan(u)-(c-z)/(sqrt((a-x)**2+(b-y)**2)), d**2-(a-x)**2-(b-y)**2-(c-z)**2]
#solutions = solve(equations, x, y, z, dict=True)

#print(simplify(solutions[0]))
#print(simplify(c - d*tan(u)/sqrt(cos(u)**(-2))))


#from sympy import sqrt, tan
#from sympy import solve
#from sympy.abc import x, y, z
#print(solve([x**2 + tan(y) - 2*z, y + 4*z], x, y, dict=True))
