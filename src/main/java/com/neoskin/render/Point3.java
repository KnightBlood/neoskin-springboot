package com.neoskin.render;

/**
 * {@code Blessing\Renderer\Point} 的移植。
 *
 * <p> {@code min/max} 的联动在 PHP 里用了 {@code &} 引用；这里用可复用的
 * {@link Bounds} 对象模拟。</p>
 */
public final class Point3 {

    /** 投影过程的共享 minX/maxX/minY/maxY（PHP 引用传参的模拟）。 */
    public static final class Bounds {
        public double minX;
        public double maxX;
        public double minY;
        public double maxY;
    }

    public double x;
    public double y;
    public double z;
    private double destX;
    private double destY;
    private double destZ;
    private boolean isProjected;
    private boolean isPreProjected;

    public Point3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** {@code project()}。 */
    public void project(double cosAlpha, double sinAlpha, double cosOmega, double sinOmega,
            Bounds bounds) {
        double yy = this.y;
        double zz = this.z;

        destX = x * cosOmega + zz * sinOmega;
        destY = x * sinAlpha * sinOmega + yy * cosAlpha - zz * sinAlpha * cosOmega;
        destZ = -x * cosAlpha * sinOmega + yy * sinAlpha + zz * cosAlpha * cosOmega;

        isProjected = true;
        bounds.minX = Math.min(bounds.minX, destX);
        bounds.maxX = Math.max(bounds.maxX, destX);
        bounds.minY = Math.min(bounds.minY, destY);
        bounds.maxY = Math.max(bounds.maxY, destY);
    }

    /** {@code preProject()}：绕局部轴先旋转。 */
    public void preProject(double dx, double dy, double dz,
            double cosAlpha, double sinAlpha, double cosOmega, double sinOmega) {
        if (isPreProjected) {
            return;
        }

        double x = this.x - dx;
        double y = this.y - dy;
        double z = this.z - dz;

        this.x = x * cosOmega + z * sinOmega + dx;
        this.y = x * sinAlpha * sinOmega + y * cosAlpha - z * sinAlpha * cosOmega + dy;
        this.z = -x * cosAlpha * sinOmega + y * sinAlpha + z * cosAlpha * cosOmega + dz;

        isPreProjected = true;
    }

    /** {@code getDepth()}。 */
    public double depth(double cosAlpha, double sinAlpha, double cosOmega, double sinOmega,
            Bounds bounds) {
        if (!isProjected) {
            project(cosAlpha, sinAlpha, cosOmega, sinOmega, bounds);
        }

        return destZ;
    }

    /** {@code isProjected()}。 */
    public boolean projected() {
        return isProjected;
    }

    public double destX() {
        return destX;
    }

    public double destY() {
        return destY;
    }

    public double destZ() {
        return destZ;
    }
}
