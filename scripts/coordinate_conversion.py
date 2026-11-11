"""Convert Korean TM (EPSG:5186) coordinates to latitude and longitude."""

import math


def tm5186_to_wgs84(x, y):
    a, f = 6378137.0, 1 / 298.257222101
    lat0, lon0, k0, fe, fn = math.radians(38), math.radians(127), 1.0, 200000.0, 600000.0
    e2 = f * (2 - f); ep2 = e2 / (1 - e2)
    def M(phi):
        return a * ((1 - e2/4 - 3*e2**2/64 - 5*e2**3/256) * phi - (3*e2/8 + 3*e2**2/32 + 45*e2**3/1024) * math.sin(2*phi)
                    + (15*e2**2/256 + 45*e2**3/1024) * math.sin(4*phi) - (35*e2**3/3072) * math.sin(6*phi))
    mu = (M(lat0) + (y - fn) / k0) / (a * (1 - e2/4 - 3*e2**2/64 - 5*e2**3/256))
    e1 = (1 - math.sqrt(1 - e2)) / (1 + math.sqrt(1 - e2))
    phi1 = mu + (3*e1/2 - 27*e1**3/32) * math.sin(2*mu) + (21*e1**2/16 - 55*e1**4/32) * math.sin(4*mu) \
           + (151*e1**3/96) * math.sin(6*mu) + (1097*e1**4/512) * math.sin(8*mu)
    s, c, t = math.sin(phi1), math.cos(phi1), math.tan(phi1)
    C1, T1 = ep2 * c * c, t * t
    N1 = a / math.sqrt(1 - e2 * s * s)
    R1 = a * (1 - e2) / (1 - e2 * s * s) ** 1.5
    D = (x - fe) / (N1 * k0)
    lat = phi1 - (N1 * t / R1) * (D*D/2 - (5 + 3*T1 + 10*C1 - 4*C1*C1 - 9*ep2) * D**4/24
                                  + (61 + 90*T1 + 298*C1 + 45*T1*T1 - 252*ep2 - 3*C1*C1) * D**6/720)
    lon = lon0 + (D - (1 + 2*T1 + C1) * D**3/6 + (5 - 2*C1 + 28*T1 - 3*C1*C1 + 8*ep2 + 24*T1*T1) * D**5/120) / c
    return math.degrees(lat), math.degrees(lon)
