package com.safeschoolroad.geo;

import java.util.List;

// 경로선 하나. 동네 범위라 평면으로 근사해 미터 단위로 계산한다
// (experiments/facility-count/count.py 의 Proj, locate, cumulative 와 같은 방식).
public final class Polyline {

    private static final double M_PER_DEG_LAT = 110540;

    private final List<LatLng> points;
    private final double lat0, lng0, kx;
    private final double[] xs, ys, cum;

    public Polyline(List<LatLng> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("경로선에는 점이 2개 이상 있어야 합니다");
        }
        this.points = List.copyOf(points);
        this.lat0 = points.get(0).lat();
        this.lng0 = points.get(0).lng();
        this.kx = 111320 * Math.cos(Math.toRadians(lat0));
        int n = points.size();
        xs = new double[n];
        ys = new double[n];
        cum = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = x(points.get(i).lng());
            ys[i] = y(points.get(i).lat());
            if (i > 0) {
                cum[i] = cum[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
            }
        }
    }

    public List<LatLng> points() {
        return points;
    }

    public double length() {
        return cum[cum.length - 1];
    }

    /** 점에서 선까지의 거리와, 출발점부터 선을 따라간 거리 */
    public record Location(double distance, double along) {
    }

    public Location locate(double lat, double lng) {
        double px = x(lng), py = y(lat);
        double best = Double.MAX_VALUE, bestAlong = 0;
        for (int i = 0; i < xs.length - 1; i++) {
            double dx = xs[i + 1] - xs[i], dy = ys[i + 1] - ys[i];
            double l2 = dx * dx + dy * dy;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - xs[i]) * dx + (py - ys[i]) * dy) / l2));
            double d = Math.hypot(px - (xs[i] + t * dx), py - (ys[i] + t * dy));
            if (d < best) {
                best = d;
                bestAlong = cum[i] + t * Math.sqrt(l2);
            }
        }
        return new Location(best, bestAlong);
    }

    /** 선을 따라 along 미터 지점의 좌표 */
    public LatLng pointAt(double along) {
        if (along <= 0) {
            return points.get(0);
        }
        for (int i = 1; i < cum.length; i++) {
            if (cum[i] >= along) {
                double seg = cum[i] - cum[i - 1];
                double t = seg == 0 ? 0 : (along - cum[i - 1]) / seg;
                LatLng a = points.get(i - 1), b = points.get(i);
                return new LatLng(a.lat() + (b.lat() - a.lat()) * t, a.lng() + (b.lng() - a.lng()) * t);
            }
        }
        return points.get(points.size() - 1);
    }

    /** 두 점 사이 거리(m). 같은 평면 근사 */
    public static double distance(LatLng a, LatLng b) {
        double kx = 111320 * Math.cos(Math.toRadians((a.lat() + b.lat()) / 2));
        return Math.hypot((a.lng() - b.lng()) * kx, (a.lat() - b.lat()) * M_PER_DEG_LAT);
    }

    /** 선을 감싸는 사각형 {남, 서, 북, 동}에 pad 미터 여유를 더한 것 */
    public double[] bounds(double padMeters) {
        double s = 90, w = 180, n = -90, e = -180;
        for (LatLng p : points) {
            s = Math.min(s, p.lat());
            n = Math.max(n, p.lat());
            w = Math.min(w, p.lng());
            e = Math.max(e, p.lng());
        }
        double padLat = padMeters / M_PER_DEG_LAT, padLng = padMeters / kx;
        return new double[]{s - padLat, w - padLng, n + padLat, e + padLng};
    }

    private double x(double lng) {
        return (lng - lng0) * kx;
    }

    private double y(double lat) {
        return (lat - lat0) * M_PER_DEG_LAT;
    }
}
