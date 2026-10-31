package com.neoskin.render;

import java.util.List;

/**
 * {@code Blessing\Renderer\Polygon} 的移植：一个四面片，颜色即 GD int。
 */
public final class FacePolygon {

    private final List<Point3> dots;
    private final int color;
    private boolean isProjected;

    public FacePolygon(List<Point3> dots, int color) {
        this.dots = dots;
        this.color = color;

        double x0 = dots.get(0).x;
        double x1 = dots.get(1).x;
        double x2 = dots.get(2).x;
        double y0 = dots.get(0).y;
        double y1 = dots.get(1).y;
        double y2 = dots.get(2).y;
        double z0 = dots.get(0).z;
        double z1 = dots.get(1).z;
        double z2 = dots.get(2).z;

        boolean sameX = x0 == x1 && x1 == x2;
        boolean sameY = y0 == y1 && y1 == y2;
        boolean sameZ = z0 == z1 && z1 == z2;

        if (!sameX && !sameY && !sameZ) {
            throw new IllegalStateException("polygon is not on a coordinate plane");
        }
    }

    /** {@code addPngPolygon()}。 */
    public void draw(GdImage image, double minX, double minY, double ratio) {
        double alpha = GdImage.gdAlphaToJava(color);

        if (alpha <= 0) {
            return;
        }

        double firstX = dots.get(0).destX();
        double firstY = dots.get(0).destY();
        boolean samePlanX = true;
        boolean samePlanY = true;

        for (Point3 dot : dots) {
            if (dot.destX() != firstX) {
                samePlanX = false;
            }
            if (dot.destY() != firstY) {
                samePlanY = false;
            }
        }

        if (samePlanX || samePlanY) {
            return;
        }

        double[] points = new double[dots.size() * 2];

        for (int i = 0; i < dots.size(); i++) {
            Point3 dot = dots.get(i);
            points[2 * i] = (dot.destX() - minX) * ratio;
            points[2 * i + 1] = (dot.destY() - minY) * ratio;
        }

        image.filledPolygon(points, color);
    }

    /** {@code project()}。 */
    public void project(double cosAlpha, double sinAlpha, double cosOmega, double sinOmega,
            Point3.Bounds bounds) {
        for (Point3 dot : dots) {
            if (!dot.projected()) {
                dot.project(cosAlpha, sinAlpha, cosOmega, sinOmega, bounds);
            }
        }

        isProjected = true;
    }

    /** {@code preProject()}。 */
    public void preProject(double dx, double dy, double dz,
            double cosAlpha, double sinAlpha, double cosOmega, double sinOmega) {
        for (Point3 dot : dots) {
            dot.preProject(dx, dy, dz, cosAlpha, sinAlpha, cosOmega, sinOmega);
        }
    }

    /** {@code isProjected()}。 */
    public boolean projected() {
        return isProjected;
    }
}
