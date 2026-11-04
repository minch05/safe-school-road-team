package com.safeschoolroad.evidence;

import com.safeschoolroad.route.Point;
import java.util.List;

/** 경로선 하나. 동네 범위라 평면으로 근사해 미터 단위로 계산한다(개인 개발본 Polyline과 같은 방식). */
public final class GeoLine {
    private static final double M_PER_DEG_LAT = 110540;
    private final List<Point> points;
    private final double lat0, lng0, kx;
    private final double[] xs, ys, cum;

    public GeoLine(List<Point> points) {
        if (points.size() < 2) throw new IllegalArgumentException("경로선에는 점이 2개 이상 있어야 합니다");
        this.points = List.copyOf(points);
        lat0 = points.get(0).lat();
        lng0 = points.get(0).lng();
        kx = 111320 * Math.cos(Math.toRadians(lat0));
        int n = points.size();
        xs = new double[n]; ys = new double[n]; cum = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = (points.get(i).lng() - lng0) * kx;
            ys[i] = (points.get(i).lat() - lat0) * M_PER_DEG_LAT;
            if (i > 0) cum[i] = cum[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        }
    }

    public double length() { return cum[cum.length - 1]; }

    /** 점에서 선까지 거리와, 출발점부터 선을 따라간 거리(m) */
    public record Location(double distance, double along) { }

    public Location locate(double lat, double lng) {
        double px = (lng - lng0) * kx, py = (lat - lat0) * M_PER_DEG_LAT;
        double best = Double.MAX_VALUE, bestAlong = 0;
        for (int i = 0; i < xs.length - 1; i++) {
            double dx = xs[i + 1] - xs[i], dy = ys[i + 1] - ys[i];
            double l2 = dx * dx + dy * dy;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - xs[i]) * dx + (py - ys[i]) * dy) / l2));
            double d = Math.hypot(px - (xs[i] + t * dx), py - (ys[i] + t * dy));
            if (d < best) { best = d; bestAlong = cum[i] + t * Math.sqrt(l2); }
        }
        return new Location(best, bestAlong);
    }

    public Point pointAt(double along) {
        if (along <= 0) return points.get(0);
        for (int i = 1; i < cum.length; i++) {
            if (cum[i] >= along) {
                double seg = cum[i] - cum[i - 1];
                double t = seg == 0 ? 0 : (along - cum[i - 1]) / seg;
                Point a = points.get(i - 1), b = points.get(i);
                return new Point(a.lat() + (b.lat() - a.lat()) * t, a.lng() + (b.lng() - a.lng()) * t);
            }
        }
        return points.get(points.size() - 1);
    }

    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double k = 111320 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.hypot((lng1 - lng2) * k, (lat1 - lat2) * M_PER_DEG_LAT);
    }

    /** 선을 감싸는 사각형 {남, 서, 북, 동}에 pad 미터 여유를 더한 것 */
    public double[] bounds(double padMeters) {
        double s = 90, w = 180, n = -90, e = -180;
        for (Point p : points) {
            s = Math.min(s, p.lat()); n = Math.max(n, p.lat());
            w = Math.min(w, p.lng()); e = Math.max(e, p.lng());
        }
        return new double[]{s - padMeters / M_PER_DEG_LAT, w - padMeters / kx, n + padMeters / M_PER_DEG_LAT, e + padMeters / kx};
    }

    /** 점이 다각형([경도, 위도] 목록) 안에 있는지 */
    public static boolean contains(List<double[]> ring, double lng, double lat) {
        boolean in = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double[] a = ring.get(i), b = ring.get(j);
            if ((a[1] > lat) != (b[1] > lat) && lng < (b[0] - a[0]) * (lat - a[1]) / (b[1] - a[1]) + a[0]) in = !in;
        }
        return in;
    }
}
