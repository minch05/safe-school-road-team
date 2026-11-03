package com.safeschoolroad.route;

public record Point(double lat, double lng) {
    public boolean inSupportedArea() {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= 37.41 && lat <= 37.72 && lng >= 126.76 && lng <= 127.19;
    }
}
