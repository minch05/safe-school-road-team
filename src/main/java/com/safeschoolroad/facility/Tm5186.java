package com.safeschoolroad.facility;

// 좌표 변환: EPSG:5186 (중부원점 TM, GRS80) → 위도·경도(WGS84).
// 서울시 횡단보도·보행등 데이터가 이 좌표계다. 표준 횡축 메르카토르 역변환 공식(Snyder).
// experiments/facility-count/count.py 의 tm5186_to_wgs84 를 그대로 옮겼다.
public final class Tm5186 {

    private static final double A = 6378137.0;
    private static final double F = 1 / 298.257222101;
    private static final double LAT0 = Math.toRadians(38);
    private static final double LON0 = Math.toRadians(127);
    private static final double K0 = 1.0;
    private static final double FALSE_EASTING = 200000.0;
    private static final double FALSE_NORTHING = 600000.0;

    private static final double E2 = F * (2 - F);
    private static final double EP2 = E2 / (1 - E2);
    private static final double M_COEF = 1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256;

    private Tm5186() {
    }

    /** @return {위도, 경도} */
    public static double[] toWgs84(double x, double y) {
        double mu = (meridianArc(LAT0) + (y - FALSE_NORTHING) / K0) / (A * M_COEF);
        double sq = Math.sqrt(1 - E2);
        double e1 = (1 - sq) / (1 + sq);
        double phi1 = mu
                + (3 * e1 / 2 - 27 * Math.pow(e1, 3) / 32) * Math.sin(2 * mu)
                + (21 * e1 * e1 / 16 - 55 * Math.pow(e1, 4) / 32) * Math.sin(4 * mu)
                + (151 * Math.pow(e1, 3) / 96) * Math.sin(6 * mu)
                + (1097 * Math.pow(e1, 4) / 512) * Math.sin(8 * mu);

        double s = Math.sin(phi1);
        double c = Math.cos(phi1);
        double t = Math.tan(phi1);
        double c1 = EP2 * c * c;
        double t1 = t * t;
        double n1 = A / Math.sqrt(1 - E2 * s * s);
        double r1 = A * (1 - E2) / Math.pow(1 - E2 * s * s, 1.5);
        double d = (x - FALSE_EASTING) / (n1 * K0);

        double lat = phi1 - (n1 * t / r1) * (d * d / 2
                - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * Math.pow(d, 4) / 24
                + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * Math.pow(d, 6) / 720);
        double lon = LON0 + (d
                - (1 + 2 * t1 + c1) * Math.pow(d, 3) / 6
                + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * Math.pow(d, 5) / 120) / c;
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon)};
    }

    private static double meridianArc(double phi) {
        double e4 = E2 * E2;
        double e6 = e4 * E2;
        return A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi
                - (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * Math.sin(2 * phi)
                + (15 * e4 / 256 + 45 * e6 / 1024) * Math.sin(4 * phi)
                - (35 * e6 / 3072) * Math.sin(6 * phi));
    }
}
