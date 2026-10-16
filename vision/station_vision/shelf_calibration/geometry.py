"""Polygon geometry checks shared by manual calibration."""
def simple_polygon(polygon):
    def orient(a, b, c):
        return float((b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]))
    n = len(polygon)
    if len(set(map(tuple, polygon))) != n:
        return False
    for i in range(n):
        for j in range(i+1, n):
            if j == i+1 or (i == 0 and j == n-1):
                continue
            a,b,c,d = polygon[i],polygon[(i+1)%n],polygon[j],polygon[(j+1)%n]
            if orient(a,b,c)*orient(a,b,d) <= 0 and orient(c,d,a)*orient(c,d,b) <= 0:
                if max(min(a[0],b[0]),min(c[0],d[0])) <= min(max(a[0],b[0]),max(c[0],d[0])) and max(min(a[1],b[1]),min(c[1],d[1])) <= min(max(a[1],b[1]),max(c[1],d[1])):
                    return False
    return True
