package com.neoskin.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code Blessing\Renderer\SkinRenderer} 的移植（PHP 1402 行 → 结构逐段对齐）。
 *
 * <p>刻意保留源码里的行为怪癖（bug-for-bug）：</p>
 * <ul>
 *   <li>体积点表按索引三元组键控，且四肢側面的 isset 守卫检查的是
 *       {@code [8*hd][j][k]}，实际写入 {@code [4*hd][j][k]}（上游原样）；</li>
 *   <li>「背面朝向」取的是 {@code getDepth} 较小的一侧，比较用
 *       {@code 旧 > 新} 的严格小于语义；</li>
 *   <li>{@code memberRotation} 对腿部的 z 偏移按 {@code sin_alpha < 0 ? 0 : 4}。</li>
 * </ul>
 */
public final class SkinRenderer {

    /** 单个立方体角点及其关联面（PHP {@code array(Point, faces)}）。 */
    private record CubePoint(Point3 point, Set<String> faces) {
    }

    /** {@code getDisplayOrder()} 的单个绘制批次（piece → faces）。 */
    private record DrawPass(String piece, Set<String> faces) {
    }

    /** {@code members_angles} 的成员角（cos/sin 四元组）。 */
    private record Angles(double cosAlpha, double sinAlpha, double cosOmega, double sinOmega) {
    }

    private static final List<String> ALL_FACES_NAMES =
            List.of("back", "right", "top", "front", "left", "bottom");

    private GdImage playerSkin;
    private boolean isNewSkinType;
    private boolean isAlex;

    private double hdRatio = 1; // PHP 为浮点除 imagesx/64

    private double vR;
    private double hR;
    private double hrh;
    private double vrll;
    private double vrrl;
    private double vrla;
    private double vrra;
    private boolean headOnly;
    private double ratio;
    private boolean layers;

    // Rotation variables in radians (3D Rendering)
    private double alpha; // Vertical rotation on the X axis.
    private double omega; // Horizontal rotation on the Y axis.
    private double cosAlpha;
    private double sinAlpha;
    private double cosOmega;
    private double sinOmega;

    private final Map<String, Angles> membersAngles = new LinkedHashMap<>();

    private Point3.Bounds bounds = new Point3.Bounds();

    private Set<String> allFaces;
    private Set<String> frontFaces;
    private Set<String> backFaces;

    private final Map<String, Map<String, Set<String>>> visibleFaces = new LinkedHashMap<>();
    private List<CubePoint> cubePoints = new ArrayList<>();

    private final Map<String, Map<String, List<FacePolygon>>> polygons = new LinkedHashMap<>();
    private final Map<String, Point3> volumePoints = new LinkedHashMap<>();

    public SkinRenderer(double ratio, boolean headOnly, double horizontalRotation,
            double verticalRotation, double horizontalRotationOfHead,
            double verticalRotationOfLeftLeg, double verticalRotationOfRightLeg,
            double verticalRotationOfLeftArm, double verticalRotationOfRightArm,
            boolean layers) {
        this.ratio = ratio;
        this.headOnly = headOnly;
        this.hR = horizontalRotation;
        this.vR = verticalRotation;
        this.hrh = horizontalRotationOfHead;
        this.vrll = verticalRotationOfLeftLeg;
        this.vrrl = verticalRotationOfRightLeg;
        this.vrla = verticalRotationOfLeftArm;
        this.vrra = verticalRotationOfRightArm;
        this.layers = layers;
    }

    /** PHP {@code new SkinRenderer($ratio, $headOnly, $horizontalRotation)}：vertical 默认 -25。 */
    public SkinRenderer(double ratio, boolean headOnly, double horizontalRotation) {
        this(ratio, headOnly, horizontalRotation, -25, 0, 0, 0, 0, 0, true);
    }

    public SkinRenderer(double ratio, boolean headOnly, double horizontalRotation,
            double verticalRotation) {
        this(ratio, headOnly, horizontalRotation, verticalRotation, 0, 0, 0, 0, 0, true);
    }

    /** {@code render()}。 */
    public GdImage render(byte[] source, boolean isAlex) {
        this.playerSkin = GdImage.make(source);
        if (playerSkin == null) {
            return null;
        }

        int sourceWidth = playerSkin.width();
        int sourceHeight = playerSkin.height();

        // prevent memory overflow
        if (sourceWidth > 256) {
            double newHeightD = sourceHeight / (sourceWidth / 256.0);
            int newHeight = (int) newHeightD;
            GdImage dest = GdImage.createEmptyCanvas(256, newHeight);
            dest.resampleFrom(playerSkin, 0, 0, 256, newHeight);
            playerSkin = dest;
        }

        this.isAlex = isAlex;
        this.hdRatio = playerSkin.width() / 64.0; // 以宽度为准（新款皮肤宽度未必等于高度）

        // check if new skin type. If both sides are equally long: new skin type
        if (playerSkin.width() == playerSkin.height()) {
            this.isNewSkinType = true;
        }

        makeBackgroundTransparent(); // make background transparent (fix for weird rendering skins)

        // Quick fix for 1.8: copy the extra layers ontop of the base layers
        if (layers) {
            fixNewSkinTypeLayers();
        }

        calculateAngles();
        facesDetermination();
        generatePolygons();
        memberRotation();
        createProjectionPlan();
        GdImage image = displayImage();
        playerSkin = null;

        return image;
    }

    // ======================================================== background

    private void makeBackgroundTransparent() {
        // check if the corner box is one solid color
        int tempValue = Integer.MIN_VALUE;
        boolean needRemove = true;

        for (int iH = 0; iH < 8; iH++) {
            for (int iV = 0; iV < 8; iV++) {
                int pixelColor = playerSkin.colorAtGd(iH, iV);

                int gdAlpha = (pixelColor >>> 24) & 0x7F;
                if (gdAlpha > 120) {
                    // the image contains transparency, nothing to do
                    needRemove = false;
                }

                if (tempValue == Integer.MIN_VALUE) {
                    tempValue = pixelColor;
                } else if (tempValue != pixelColor) {
                    // Cannot determine a background color, file is probably fine
                    needRemove = false;
                }
            }
        }

        int imgX = playerSkin.width();
        int imgY = playerSkin.height();
        GdImage dst = GdImage.createEmptyCanvas(imgX, imgY);

        int fillGd;
        int bgRgb = 0;

        if (needRemove) {
            // the entire block is one solid color. Use this color to clear the background.
            bgRgb = tempValue & 0x00FFFFFF;
            // imagecolorallocate → 不透明填色
            fillGd = bgRgb;
        } else {
            fillGd = (0xFF << 24); // imagecolorallocate(0,0,0) → 不透明黑
        }

        // fill the areas that should not be transparent
        double positionMultiply = imgX / 64.0;

        // head
        rectF(dst, 8, 0, 23, 7, positionMultiply, fillGd);
        rectF(dst, 0, 8, 31, 15, positionMultiply, fillGd);

        // right leg, body, right arm
        rectF(dst, 4, 16, 11, 19, positionMultiply, fillGd);
        rectF(dst, 20, 16, 35, 19, positionMultiply, fillGd);
        rectF(dst, 44, 16, 51, 19, positionMultiply, fillGd);
        rectF(dst, 0, 20, 54, 31, positionMultiply, fillGd);

        // left leg, left arm
        rectF(dst, 20, 48, 27, 51, positionMultiply, fillGd);
        rectF(dst, 36, 48, 43, 51, positionMultiply, fillGd);
        rectF(dst, 16, 52, 47, 63, positionMultiply, fillGd);

        // imagecopy($dst, $playerSkin, 0, 0, 0, 0, $imgX, $imgY)
        // 被标记为「背景色」或全透明的源像素不覆盖底色（imagecolortransparent 的等效）
        for (int y = 0; y < imgY; y++) {
            for (int x = 0; x < imgX; x++) {
                int src = playerSkin.pixelAt(x, y);

                if (needRemove && (src & 0x00FFFFFF) == bgRgb) {
                    continue;
                }
                int srcGdAlpha = 127 - Math.round(((src >>> 24) & 0xFF) * 127 / 255f);
                if (srcGdAlpha >= 127) {
                    continue;
                }

                dst.pixelSet(x, y, src);
            }
        }

        playerSkin = dst;
    }

    private static void rectF(GdImage dst, int x1, int y1, int x2, int y2, double mul, int gdColor) {
        dst.filledRectangle((int) (x1 * mul), (int) (y1 * mul),
                (int) (x2 * mul), (int) (y2 * mul), gdColor);
    }

    /**
     * Function copies the extra layers of a 1.8 skin onto the base layers so that it
     * will still show. QUICK FIX, NEEDS BETTER FIX.
     */
    private void fixNewSkinTypeLayers() {
        if (!isNewSkinType) {
            return;
        }

        copySelf(playerSkin, 0, 16, 0, 32, 56, 16);  // RL2, BODY2, RA2
        copySelf(playerSkin, 16, 48, 0, 48, 16, 16); // LL2
        copySelf(playerSkin, 32, 48, 48, 48, 16, 16); // LA2
    }

    /** PHP {@code imagecopy($skin, $skin, ...)}：自拷贝（先快照再写，避免覆盖读取）。 */
    private static void copySelf(GdImage img, int dx, int dy, int sx, int sy, int w, int h) {
        List<int[]> snapshot = new ArrayList<>();
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                snapshot.add(new int[] {sx + i, sy + j, img.pixelAt(sx + i, sy + j)});
            }
        }
        for (int[] p : snapshot) {
            img.pixelSet(dx + (p[0] - sx), dy + (p[1] - sy), p[2]);
        }
    }

    // ======================================================== angles

    private void calculateAngles() {
        // Rotation variables in radians (3D Rendering)
        alpha = Math.toRadians(vR); // Vertical rotation on the X axis.
        omega = Math.toRadians(hR); // Horizontal rotation on the Y axis.

        // Cosine and Sine values
        cosAlpha = Math.cos(alpha);
        sinAlpha = Math.sin(alpha);
        cosOmega = Math.cos(omega);
        sinOmega = Math.sin(omega);

        membersAngles.put("torso", anglesOf(0, 0));

        double alphaHead = 0;
        double omegaHead = Math.toRadians(hrh);
        membersAngles.put("head", anglesOf(alphaHead, omegaHead)); // Head and helmet get the same
        membersAngles.put("helmet", membersAngles.get("head"));

        membersAngles.put("rightArm", anglesOf(Math.toRadians(vrra), 0));
        membersAngles.put("leftArm", anglesOf(Math.toRadians(vrla), 0));
        membersAngles.put("rightLeg", anglesOf(Math.toRadians(vrrl), 0));
        membersAngles.put("leftLeg", anglesOf(Math.toRadians(vrll), 0));

        bounds = new Point3.Bounds(); // minX = maxX = minY = maxY = 0
    }

    private static Angles anglesOf(double alphaRad, double omegaRad) {
        return new Angles(Math.cos(alphaRad), Math.sin(alphaRad),
                Math.cos(omegaRad), Math.sin(omegaRad));
    }

    // ======================================================== faces

    private void facesDetermination() {
        double ca = cosAlpha;
        double sa = sinAlpha;
        double co = cosOmega;
        double so = sinOmega;

        for (String part : List.of("head", "torso", "rightArm", "leftArm",
                "rightLeg", "leftLeg")) {
            visibleFaces.put(part, new LinkedHashMap<>());
        }

        allFaces = new LinkedHashSet<>(ALL_FACES_NAMES);

        // Loop each preProject and Project then calculate the visible faces for each
        for (String part : visibleFaces.keySet()) {
            CubePoint cubeMaxDepthFaces = null;

            setCubePoints();

            for (CubePoint cubePoint : cubePoints) {
                Angles m = membersAngles.get(part);
                cubePoint.point().preProject(0, 0, 0,
                        m.cosAlpha(), m.sinAlpha(), m.cosOmega(), m.sinOmega());
                cubePoint.point().project(ca, sa, co, so, bounds);

                if (cubeMaxDepthFaces == null) {
                    cubeMaxDepthFaces = cubePoint;
                } else if (cubeMaxDepthFaces.point()
                        .depth(ca, sa, co, so, bounds) > cubePoint.point()
                        .depth(ca, sa, co, so, bounds)) {
                    cubeMaxDepthFaces = cubePoint;
                }
            }

            Map<String, Set<String>> v = visibleFaces.get(part);
            v.put("back", new LinkedHashSet<>(cubeMaxDepthFaces.faces()));
            v.put("front", arrayDiff(allFaces, v.get("back")));
        }

        setCubePoints();

        CubePoint cubeMaxDepthFaces = null;
        for (CubePoint cubePoint : cubePoints) {
            cubePoint.point().project(ca, sa, co, so, bounds);

            if (cubeMaxDepthFaces == null) {
                cubeMaxDepthFaces = cubePoint;
            } else if (cubeMaxDepthFaces.point()
                    .depth(ca, sa, co, so, bounds) > cubePoint.point()
                    .depth(ca, sa, co, so, bounds)) {
                cubeMaxDepthFaces = cubePoint;
            }

            backFaces = new LinkedHashSet<>(cubeMaxDepthFaces.faces());
            frontFaces = arrayDiff(allFaces, backFaces);
        }
    }

    /** {@code array_diff($all, $remove)}：保留 $all 中的相对顺序。 */
    private static Set<String> arrayDiff(Set<String> all, Set<String> remove) {
        Set<String> result = new LinkedHashSet<>();
        for (String f : all) {
            if (!remove.contains(f)) {
                result.add(f);
            }
        }

        return result;
    }

    private void setCubePoints() {
        cubePoints = new ArrayList<>();
        cubePoints.add(new CubePoint(new Point3(0, 0, 0),
                faces("back", "right", "top"))); // 0
        cubePoints.add(new CubePoint(new Point3(0, 0, 1),
                faces("front", "right", "top"))); // 1
        cubePoints.add(new CubePoint(new Point3(0, 1, 0),
                faces("back", "right", "bottom"))); // 2
        cubePoints.add(new CubePoint(new Point3(0, 1, 1),
                faces("front", "right", "bottom"))); // 3
        cubePoints.add(new CubePoint(new Point3(1, 0, 0),
                faces("back", "left", "top"))); // 4
        cubePoints.add(new CubePoint(new Point3(1, 0, 1),
                faces("front", "left", "top"))); // 5
        cubePoints.add(new CubePoint(new Point3(1, 1, 0),
                faces("back", "left", "bottom"))); // 6
        cubePoints.add(new CubePoint(new Point3(1, 1, 1),
                faces("front", "left", "bottom"))); // 7
    }

    private static Set<String> faces(String... names) {
        return new LinkedHashSet<>(Arrays.asList(names));
    }

    // ======================================================== polygons

    private void generatePolygons() {
        polygons.clear();

        for (String part : List.of("helmet", "head", "torso", "rightArm",
                "leftArm", "rightLeg", "leftLeg")) {
            Map<String, List<FacePolygon>> m = new LinkedHashMap<>();
            for (String f : ALL_FACES_NAMES) {
                m.put(f, new ArrayList<>());
            }
            polygons.put(part, m);
        }

        head();
        helmet();
        if (!headOnly) {
            torso();
            rightArm();
            leftArm();
            rightLeg();
            leftLeg();
        }
    }

    /** 体积点表：按「索引三元组」键控（PHP {@code $volume_points[$i][$j][$k]}）。 */
    private String key(double i, double j, double k) {
        return i + "|" + j + "|" + k;
    }

    private boolean has(double i, double j, double k) {
        return volumePoints.containsKey(key(i, j, k));
    }

    private void put(double i, double j, double k, double x, double y, double z) {
        volumePoints.put(key(i, j, k), new Point3(x, y, z));
    }

    private Point3 pt(double i, double j, double k) {
        Point3 p = volumePoints.get(key(i, j, k));
        if (p == null) {
            throw new IllegalStateException(
                    "volume point missing: " + key(i, j, k) + " (part under construction)");
        }

        return p;
    }

    private int color(int x, int y) {
        return playerSkin.colorAtGd(x, y);
    }

    private void quad(String part, String face, Point3 a, Point3 b, Point3 c, Point3 d, int col) {
        polygons.get(part).get(face).add(new FacePolygon(List.of(a, b, c, d), col));
    }

    // ---------------------------------------------------------------- HEAD

    private void head() {
        volumePoints.clear();
        double hd = hdRatio;

        for (int i = 0; i < 9 * hd; i++) {
            for (int j = 0; j < 9 * hd; j++) {
                if (!has(i, j, -2 * hd)) {
                    put(i, j, -2 * hd, i, j, -2 * hd);
                }
                if (!has(i, j, 6 * hd)) {
                    put(i, j, 6 * hd, i, j, 6 * hd);
                }
            }
        }
        for (int j = 0; j < 9 * hd; j++) {
            for (int k = (int) (-2 * hd); k < 7 * hd; k++) {
                if (!has(0, j, k)) {
                    put(0, j, k, 0, j, k);
                }
                if (!has(8 * hd, j, k)) {
                    put(8 * hd, j, k, 8 * hd, j, k);
                }
            }
        }
        for (int i = 0; i < 9 * hd; i++) {
            for (int k = (int) (-2 * hd); k < 7 * hd; k++) {
                if (!has(i, 0, k)) {
                    put(i, 0, k, i, 0, k);
                }
                if (!has(i, 8 * hd, k)) {
                    put(i, 8 * hd, k, i, 8 * hd, k);
                }
            }
        }
        for (int i = 0; i < 8 * hd; i++) {
            for (int j = 0; j < 8 * hd; j++) {
                quad("head", "back", pt(i, j, -2 * hd), pt(i + 1, j, -2 * hd),
                        pt(i + 1, j + 1, -2 * hd), pt(i, j + 1, -2 * hd),
                        color((int) ((32 * hd - 1) - i), (int) (8 * hd + j)));
                quad("head", "front", pt(i, j, 6 * hd), pt(i + 1, j, 6 * hd),
                        pt(i + 1, j + 1, 6 * hd), pt(i, j + 1, 6 * hd),
                        color((int) (8 * hd + i), (int) (8 * hd + j)));
            }
        }
        for (int j = 0; j < 8 * hd; j++) {
            for (int k = (int) (-2 * hd); k < 6 * hd; k++) {
                quad("head", "right", pt(0, j, k), pt(0, j, k + 1),
                        pt(0, j + 1, k + 1), pt(0, j + 1, k),
                        color((int) (k + 2 * hd), (int) (8 * hd + j)));
                quad("head", "left", pt(8 * hd, j, k), pt(8 * hd, j, k + 1),
                        pt(8 * hd, j + 1, k + 1), pt(8 * hd, j + 1, k),
                        color((int) ((24 * hd - 1) - k - 2 * hd), (int) (8 * hd + j)));
            }
        }
        for (int i = 0; i < 8 * hd; i++) {
            for (int k = (int) (-2 * hd); k < 6 * hd; k++) {
                quad("head", "top", pt(i, 0, k), pt(i + 1, 0, k),
                        pt(i + 1, 0, k + 1), pt(i, 0, k + 1),
                        color((int) (8 * hd + i), (int) (k + 2 * hd)));
                quad("head", "bottom", pt(i, 8 * hd, k), pt(i + 1, 8 * hd, k),
                        pt(i + 1, 8 * hd, k + 1), pt(i, 8 * hd, k + 1),
                        color((int) (16 * hd + i), (int) (2 * hd + k)));
            }
        }
    }

    // ---------------------------------------------------------------- HELMET

    private void helmet() {
        volumePoints.clear();
        double hd = hdRatio;

        for (int i = 0; i < 9 * hd; i++) {
            for (int j = 0; j < 9 * hd; j++) {
                if (!has(i, j, -2 * hd)) {
                    put(i, j, -2 * hd, i * 9 / 8.0 - 0.5 * hd, j * 9 / 8.0 - 0.5 * hd, -2.5 * hd);
                }
                if (!has(i, j, 6 * hd)) {
                    put(i, j, 6 * hd, i * 9 / 8.0 - 0.5 * hd, j * 9 / 8.0 - 0.5 * hd, 6.5 * hd);
                }
            }
        }
        for (int j = 0; j < 9 * hd; j++) {
            for (int k = (int) (-2 * hd); k < 7 * hd; k++) {
                if (!has(0, j, k)) {
                    put(0, j, k, -0.5 * hd, j * 9 / 8.0 - 0.5 * hd, k * 9 / 8.0 - 0.5 * hd);
                }
                if (!has(8 * hd, j, k)) {
                    put(8 * hd, j, k, 8.5 * hd, j * 9 / 8.0 - 0.5 * hd, k * 9 / 8.0 - 0.5 * hd);
                }
            }
        }
        for (int i = 0; i < 9 * hd; i++) {
            for (int k = (int) (-2 * hd); k < 7 * hd; k++) {
                if (!has(i, 0, k)) {
                    put(i, 0, k, i * 9 / 8.0 - 0.5 * hd, -0.5 * hd, k * 9 / 8.0 - 0.5 * hd);
                }
                if (!has(i, 8 * hd, k)) {
                    put(i, 8 * hd, k, i * 9 / 8.0 - 0.5 * hd, 8.5 * hd, k * 9 / 8.0 - 0.5 * hd);
                }
            }
        }
        for (int i = 0; i < 8 * hd; i++) {
            for (int j = 0; j < 8 * hd; j++) {
                quad("helmet", "back", pt(i, j, -2 * hd), pt(i + 1, j, -2 * hd),
                        pt(i + 1, j + 1, -2 * hd), pt(i, j + 1, -2 * hd),
                        color((int) (32 * hd + (32 * hd - 1) - i), (int) (8 * hd + j)));
                quad("helmet", "front", pt(i, j, 6 * hd), pt(i + 1, j, 6 * hd),
                        pt(i + 1, j + 1, 6 * hd), pt(i, j + 1, 6 * hd),
                        color((int) (32 * hd + 8 * hd + i), (int) (8 * hd + j)));
            }
        }
        for (int j = 0; j < 8 * hd; j++) {
            for (int k = (int) (-2 * hd); k < 6 * hd; k++) {
                quad("helmet", "right", pt(0, j, k), pt(0, j, k + 1),
                        pt(0, j + 1, k + 1), pt(0, j + 1, k),
                        color((int) (32 * hd + k + 2 * hd), (int) (8 * hd + j)));
                quad("helmet", "left", pt(8 * hd, j, k), pt(8 * hd, j, k + 1),
                        pt(8 * hd, j + 1, k + 1), pt(8 * hd, j + 1, k),
                        color((int) (32 * hd + (24 * hd - 1) - k - 2 * hd), (int) (8 * hd + j)));
            }
        }
        for (int i = 0; i < 8 * hd; i++) {
            for (int k = (int) (-2 * hd); k < 6 * hd; k++) {
                quad("helmet", "top", pt(i, 0, k), pt(i + 1, 0, k),
                        pt(i + 1, 0, k + 1), pt(i, 0, k + 1),
                        color((int) (32 * hd + 8 * hd + i), (int) (k + 2 * hd)));
                quad("helmet", "bottom", pt(i, 8 * hd, k), pt(i + 1, 8 * hd, k),
                        pt(i + 1, 8 * hd, k + 1), pt(i, 8 * hd, k + 1),
                        color((int) (32 * hd + 16 * hd + i), (int) (2 * hd + k)));
            }
        }
    }

    // ---------------------------------------------------------------- TORSO

    private void torso() {
        volumePoints.clear();
        double hd = hdRatio;

        for (int i = 0; i < 9 * hd; i++) {
            for (int j = 0; j < 13 * hd; j++) {
                if (!has(i, j, 0)) {
                    put(i, j, 0, i, j + 8 * hd, 0);
                }
                if (!has(i, j, 4 * hd)) {
                    put(i, j, 4 * hd, i, j + 8 * hd, 4 * hd);
                }
            }
        }
        for (int j = 0; j < 13 * hd; j++) {
            for (int k = 0; k < 5 * hd; k++) {
                if (!has(0, j, k)) {
                    put(0, j, k, 0, j + 8 * hd, k);
                }
                if (!has(8 * hd, j, k)) {
                    put(8 * hd, j, k, 8 * hd, j + 8 * hd, k);
                }
            }
        }
        for (int i = 0; i < 9 * hd; i++) {
            for (int k = 0; k < 5 * hd; k++) {
                if (!has(i, 0, k)) {
                    put(i, 0, k, i, 0 + 8 * hd, k);
                }
                if (!has(i, 12 * hd, k)) {
                    put(i, 12 * hd, k, i, 12 * hd + 8 * hd, k);
                }
            }
        }
        for (int i = 0; i < 8 * hd; i++) {
            for (int j = 0; j < 12 * hd; j++) {
                quad("torso", "back", pt(i, j, 0), pt(i + 1, j, 0),
                        pt(i + 1, j + 1, 0), pt(i, j + 1, 0),
                        color((int) ((40 * hd - 1) - i), (int) (20 * hd + j)));
                quad("torso", "front", pt(i, j, 4 * hd), pt(i + 1, j, 4 * hd),
                        pt(i + 1, j + 1, 4 * hd), pt(i, j + 1, 4 * hd),
                        color((int) (20 * hd + i), (int) (20 * hd + j)));
            }
        }
        for (int j = 0; j < 12 * hd; j++) {
            for (int k = 0; k < 4 * hd; k++) {
                quad("torso", "right", pt(0, j, k), pt(0, j, k + 1),
                        pt(0, j + 1, k + 1), pt(0, j + 1, k),
                        color((int) (16 * hd + k), (int) (20 * hd + j)));
                quad("torso", "left", pt(8 * hd, j, k), pt(8 * hd, j, k + 1),
                        pt(8 * hd, j + 1, k + 1), pt(8 * hd, j + 1, k),
                        color((int) ((32 * hd - 1) - k), (int) (20 * hd + j)));
            }
        }
        for (int i = 0; i < 8 * hd; i++) {
            for (int k = 0; k < 4 * hd; k++) {
                quad("torso", "top", pt(i, 0, k), pt(i + 1, 0, k),
                        pt(i + 1, 0, k + 1), pt(i, 0, k + 1),
                        color((int) (20 * hd + i), (int) (16 * hd + k)));
                quad("torso", "bottom", pt(i, 12 * hd, k), pt(i + 1, 12 * hd, k),
                        pt(i + 1, 12 * hd, k + 1), pt(i, 12 * hd, k + 1),
                        color((int) (28 * hd + i), (int) ((20 * hd - 1) - k)));
            }
        }
    }

    // ---------------------------------------------------------------- RIGHT ARM

    private void rightArm() {
        volumePoints.clear();
        double hd = hdRatio;

        for (int i = 0; i < 9 * hd; i++) {
            for (int j = 0; j < 13 * hd; j++) {
                if (!has(i, j, 0)) {
                    put(i, j, 0, i - 4 * hd, j + 8 * hd, 0);
                }
                if (!has(i, j, 4 * hd)) {
                    put(i, j, 4 * hd, i - 4 * hd, j + 8 * hd, 4 * hd);
                }
            }
        }
        for (int j = 0; j < 13 * hd; j++) {
            for (int k = 0; k < 5 * hd; k++) {
                if (!has(0, j, k)) {
                    put(0, j, k, 0 - 4 * hd, j + 8 * hd, k);
                }
                // 上游原样：守卫检查 [8*hd]，写入 [4*hd]，坐标 x = 4*hd - 4*hd = 0
                if (!has(8 * hd, j, k)) {
                    put(4 * hd, j, k, 4 * hd - 4 * hd, j + 8 * hd, k);
                }
            }
        }
        for (int i = 0; i < 9 * hd; i++) {
            for (int k = 0; k < 5 * hd; k++) {
                if (!has(i, 0, k)) {
                    put(i, 0, k, i - 4 * hd, 0 + 8 * hd, k);
                }
                if (!has(i, 12 * hd, k)) {
                    put(i, 12 * hd, k, i - 4 * hd, 12 * hd + 8 * hd, k);
                }
            }
        }
        if (isAlex) {
            for (int i = 0; i < 3 * hd; i++) {
                for (int j = 0; j < 12 * hd; j++) {
                    quad("rightArm", "back", pt(i, j, 0), pt(i + 1, j, 0),
                            pt(i + 1, j + 1, 0), pt(i, j + 1, 0),
                            color((int) ((51 * hd - 1) - i), (int) (20 * hd + j)));
                    quad("rightArm", "front", pt(i, j, 4 * hd), pt(i + 1, j, 4 * hd),
                            pt(i + 1, j + 1, 4 * hd), pt(i, j + 1, 4 * hd),
                            color((int) (44 * hd + i), (int) (20 * hd + j)));
                }
            }
        } else {
            for (int i = 0; i < 4 * hd; i++) {
                for (int j = 0; j < 12 * hd; j++) {
                    quad("rightArm", "back", pt(i, j, 0), pt(i + 1, j, 0),
                            pt(i + 1, j + 1, 0), pt(i, j + 1, 0),
                            color((int) ((56 * hd - 1) - i), (int) (20 * hd + j)));
                    quad("rightArm", "front", pt(i, j, 4 * hd), pt(i + 1, j, 4 * hd),
                            pt(i + 1, j + 1, 4 * hd), pt(i, j + 1, 4 * hd),
                            color((int) (44 * hd + i), (int) (20 * hd + j)));
                }
            }
        }
        for (int j = 0; j < 12 * hd; j++) {
            for (int k = 0; k < 4 * hd; k++) {
                double rightOffsetX = (isAlex ? 47 : 40) * hd;
                quad("rightArm", "right", pt(0, j, k), pt(0, j, k + 1),
                        pt(0, j + 1, k + 1), pt(0, j + 1, k),
                        color((int) (rightOffsetX + k), (int) (20 * hd + j)));
                double leftOffsetX = (isAlex ? 40 : 52) * hd;
                quad("rightArm", "left", pt(4 * hd, j, k), pt(4 * hd, j, k + 1),
                        pt(4 * hd, j + 1, k + 1), pt(4 * hd, j + 1, k),
                        color((int) ((leftOffsetX - 1) - k), (int) (20 * hd + j)));
            }
        }
        if (isAlex) {
            for (int i = 0; i < 3 * hd; i++) {
                for (int k = 0; k < 4 * hd; k++) {
                    quad("rightArm", "top", pt(i, 0, k), pt(i + 1, 0, k),
                            pt(i + 1, 0, k + 1), pt(i, 0, k + 1),
                            color((int) (44 * hd + i), (int) (16 * hd + k)));
                    quad("rightArm", "bottom", pt(i, 12 * hd, k), pt(i + 1, 12 * hd, k),
                            pt(i + 1, 12 * hd, k + 1), pt(i, 12 * hd, k + 1),
                            color((int) (47 * hd + i), (int) (16 * hd + k)));
                }
            }
        } else {
            for (int i = 0; i < 4 * hd; i++) {
                for (int k = 0; k < 4 * hd; k++) {
                    quad("rightArm", "top", pt(i, 0, k), pt(i + 1, 0, k),
                            pt(i + 1, 0, k + 1), pt(i, 0, k + 1),
                            color((int) (44 * hd + i), (int) (16 * hd + k)));
                    quad("rightArm", "bottom", pt(i, 12 * hd, k), pt(i + 1, 12 * hd, k),
                            pt(i + 1, 12 * hd, k + 1), pt(i, 12 * hd, k + 1),
                            color((int) (48 * hd + i), (int) (16 * hd + k)));
                }
            }
        }
    }

    // ---------------------------------------------------------------- LEFT ARM

    private void leftArm() {
        volumePoints.clear();
        double hd = hdRatio;

        for (int i = 0; i < 9 * hd; i++) {
            for (int j = 0; j < 13 * hd; j++) {
                if (!has(i, j, 0)) {
                    put(i, j, 0, i + 8 * hd, j + 8 * hd, 0);
                }
                if (!has(i, j, 4 * hd)) {
                    put(i, j, 4 * hd, i + 8 * hd, j + 8 * hd, 4 * hd);
                }
            }
        }
        for (int j = 0; j < 13 * hd; j++) {
            for (int k = 0; k < 5 * hd; k++) {
                if (!has(0, j, k)) {
                    put(0, j, k, 8 * hd, j + 8 * hd, k);
                }
                // 上游原样：守卫检查 [8*hd]，写入 [4*hd]，坐标 x = 4*hd + 8*hd
                if (!has(8 * hd, j, k)) {
                    put(4 * hd, j, k, 4 * hd + 8 * hd, j + 8 * hd, k);
                }
            }
        }
        for (int i = 0; i < 9 * hd; i++) {
            for (int k = 0; k < 5 * hd; k++) {
                if (!has(i, 0, k)) {
                    put(i, 0, k, i + 8 * hd, 0 + 8 * hd, k);
                }
                if (!has(i, 12 * hd, k)) {
                    put(i, 12 * hd, k, i + 8 * hd, 12 * hd + 8 * hd, k);
                }
            }
        }
        if (isAlex) {
            for (int i = 0; i < 3 * hd; i++) {
                for (int j = 0; j < 12 * hd; j++) {
                    quad("leftArm", "back", pt(i, j, 0), pt(i + 1, j, 0),
                            pt(i + 1, j + 1, 0), pt(i, j + 1, 0),
                            color((int) (43 * hd + i), (int) (52 * hd + j)));
                    quad("leftArm", "front", pt(i, j, 4 * hd), pt(i + 1, j, 4 * hd),
                            pt(i + 1, j + 1, 4 * hd), pt(i, j + 1, 4 * hd),
                            color((int) (36 * hd + i), (int) (52 * hd + j)));
                }
            }
        } else {
            for (int i = 0; i < 4 * hd; i++) {
                for (int j = 0; j < 12 * hd; j++) {
                    int color1;
                    int color2;

                    if (isNewSkinType) {
                        color1 = color((int) (47 * hd - i), (int) (52 * hd + j)); // from right to left
                        color2 = color((int) (36 * hd + i), (int) (52 * hd + j)); // from left to right
                    } else {
                        color1 = color((int) ((56 * hd - 1) - ((4 * hd - 1) - i)),
                                (int) (20 * hd + j));
                        color2 = color((int) (44 * hd + ((4 * hd - 1) - i)), (int) (20 * hd + j));
                    }

                    quad("leftArm", "back", pt(i, j, 0), pt(i + 1, j, 0),
                            pt(i + 1, j + 1, 0), pt(i, j + 1, 0), color1);
                    quad("leftArm", "front", pt(i, j, 4 * hd), pt(i + 1, j, 4 * hd),
                            pt(i + 1, j + 1, 4 * hd), pt(i, j + 1, 4 * hd), color2);
                }
            }
        }
        for (int j = 0; j < 12 * hd; j++) {
            for (int k = 0; k < 4 * hd; k++) {
                int color1;
                int color2;

                if (isNewSkinType) {
                    color1 = color((int) (32 * hd + k), (int) (52 * hd + j)); // from left to right
                    color2 = color((int) (43 * hd - k), (int) (52 * hd + j)); // from right to left
                } else {
                    color1 = color((int) (40 * hd + ((4 * hd - 1) - k)), (int) (20 * hd + j));
                    color2 = color((int) ((52 * hd - 1) - ((4 * hd - 1) - k)), (int) (20 * hd + j));
                }

                quad("leftArm", "right", pt(0, j, k), pt(0, j, k + 1),
                        pt(0, j + 1, k + 1), pt(0, j + 1, k), color1);
                quad("leftArm", "left", pt(4 * hd, j, k), pt(4 * hd, j, k + 1),
                        pt(4 * hd, j + 1, k + 1), pt(4 * hd, j + 1, k), color2);
            }
        }
        if (isAlex) {
            for (int i = 0; i < 3 * hd; i++) {
                for (int k = 0; k < 4 * hd; k++) {
                    quad("leftArm", "top", pt(i, 0, k), pt(i + 1, 0, k),
                            pt(i + 1, 0, k + 1), pt(i, 0, k + 1),
                            color((int) (36 * hd + i), (int) (48 * hd + k)));
                    quad("leftArm", "bottom", pt(i, 12 * hd, k), pt(i + 1, 12 * hd, k),
                            pt(i + 1, 12 * hd, k + 1), pt(i, 12 * hd, k + 1),
                            color((int) (39 * hd + i), (int) (48 * hd + k)));
                }
            }
        } else {
            for (int i = 0; i < 4 * hd; i++) {
                for (int k = 0; k < 4 * hd; k++) {
                    int color1;
                    int color2;

                    if (isNewSkinType) {
                        color1 = color((int) (36 * hd + i), (int) (48 * hd + k)); // from left to right
                        color2 = color((int) (40 * hd + i), (int) (48 * hd + k)); // from left to right
                    } else {
                        color1 = color((int) (44 * hd + ((4 * hd - 1) - i)), (int) (16 * hd + k));
                        color2 = color((int) (48 * hd + ((4 * hd - 1) - i)),
                                (int) ((20 * hd - 1) - k));
                    }

                    quad("leftArm", "top", pt(i, 0, k), pt(i + 1, 0, k),
                            pt(i + 1, 0, k + 1), pt(i, 0, k + 1), color1);
                    quad("leftArm", "bottom", pt(i, 12 * hd, k), pt(i + 1, 12 * hd, k),
                            pt(i + 1, 12 * hd, k + 1), pt(i, 12 * hd, k + 1), color2);
                }
            }
        }
    }

    // ---------------------------------------------------------------- RIGHT LEG

    private void rightLeg() {
        volumePoints.clear();
        double hd = hdRatio;

        for (int i = 0; i < 9 * hd; i++) {
            for (int j = 0; j < 13 * hd; j++) {
                if (!has(i, j, 0)) {
                    put(i, j, 0, i, j + 20 * hd, 0);
                }
                if (!has(i, j, 4 * hd)) {
                    put(i, j, 4 * hd, i, j + 20 * hd, 4 * hd);
                }
            }
        }
        for (int j = 0; j < 13 * hd; j++) {
            for (int k = 0; k < 5 * hd; k++) {
                if (!has(0, j, k)) {
                    put(0, j, k, 0, j + 20 * hd, k);
                }
                // 上游原样：守卫检查 [8*hd]，写入 [4*hd]，坐标 x = 4*hd
                if (!has(8 * hd, j, k)) {
                    put(4 * hd, j, k, 4 * hd, j + 20 * hd, k);
                }
            }
        }
        for (int i = 0; i < 9 * hd; i++) {
            for (int k = 0; k < 5 * hd; k++) {
                if (!has(i, 0, k)) {
                    put(i, 0, k, i, 0 + 20 * hd, k);
                }
                if (!has(i, 12 * hd, k)) {
                    put(i, 12 * hd, k, i, 12 * hd + 20 * hd, k);
                }
            }
        }
        for (int i = 0; i < 4 * hd; i++) {
            for (int j = 0; j < 12 * hd; j++) {
                quad("rightLeg", "back", pt(i, j, 0), pt(i + 1, j, 0),
                        pt(i + 1, j + 1, 0), pt(i, j + 1, 0),
                        color((int) ((16 * hd - 1) - i), (int) (20 * hd + j)));
                quad("rightLeg", "front", pt(i, j, 4 * hd), pt(i + 1, j, 4 * hd),
                        pt(i + 1, j + 1, 4 * hd), pt(i, j + 1, 4 * hd),
                        color((int) (4 * hd + i), (int) (20 * hd + j)));
            }
        }
        for (int j = 0; j < 12 * hd; j++) {
            for (int k = 0; k < 4 * hd; k++) {
                quad("rightLeg", "right", pt(0, j, k), pt(0, j, k + 1),
                        pt(0, j + 1, k + 1), pt(0, j + 1, k),
                        color((int) (0 + k), (int) (20 * hd + j)));
                quad("rightLeg", "left", pt(4 * hd, j, k), pt(4 * hd, j, k + 1),
                        pt(4 * hd, j + 1, k + 1), pt(4 * hd, j + 1, k),
                        color((int) ((12 * hd - 1) - k), (int) (20 * hd + j)));
            }
        }
        for (int i = 0; i < 4 * hd; i++) {
            for (int k = 0; k < 4 * hd; k++) {
                quad("rightLeg", "top", pt(i, 0, k), pt(i + 1, 0, k),
                        pt(i + 1, 0, k + 1), pt(i, 0, k + 1),
                        color((int) (4 * hd + i), (int) (16 * hd + k)));
                quad("rightLeg", "bottom", pt(i, 12 * hd, k), pt(i + 1, 12 * hd, k),
                        pt(i + 1, 12 * hd, k + 1), pt(i, 12 * hd, k + 1),
                        color((int) (8 * hd + i), (int) (16 * hd + k)));
            }
        }
    }

    // ---------------------------------------------------------------- LEFT LEG

    private void leftLeg() {
        volumePoints.clear();
        double hd = hdRatio;

        for (int i = 0; i < 9 * hd; i++) {
            for (int j = 0; j < 13 * hd; j++) {
                if (!has(i, j, 0)) {
                    put(i, j, 0, i + 4 * hd, j + 20 * hd, 0);
                }
                if (!has(i, j, 4 * hd)) {
                    put(i, j, 4 * hd, i + 4 * hd, j + 20 * hd, 4 * hd);
                }
            }
        }
        for (int j = 0; j < 13 * hd; j++) {
            for (int k = 0; k < 5 * hd; k++) {
                if (!has(0, j, k)) {
                    put(0, j, k, 0 + 4 * hd, j + 20 * hd, k);
                }
                // 上游原样：守卫检查 [8*hd]，写入 [4*hd]，坐标 x = 4*hd + 4*hd
                if (!has(8 * hd, j, k)) {
                    put(4 * hd, j, k, 4 * hd + 4 * hd, j + 20 * hd, k);
                }
            }
        }
        for (int i = 0; i < 9 * hd; i++) {
            for (int k = 0; k < 5 * hd; k++) {
                if (!has(i, 0, k)) {
                    put(i, 0, k, i + 4 * hd, 0 + 20 * hd, k);
                }
                if (!has(i, 12 * hd, k)) {
                    put(i, 12 * hd, k, i + 4 * hd, 12 * hd + 20 * hd, k);
                }
            }
        }
        for (int i = 0; i < 4 * hd; i++) {
            for (int j = 0; j < 12 * hd; j++) {
                int color1;
                int color2;

                if (isNewSkinType) {
                    color1 = color((int) (31 * hd - i), (int) (52 * hd + j)); // from right to left
                    color2 = color((int) (20 * hd + i), (int) (52 * hd + j)); // from left to right
                } else {
                    color1 = color((int) ((16 * hd - 1) - ((4 * hd - 1) - i)), (int) (20 * hd + j));
                    color2 = color((int) (4 * hd + ((4 * hd - 1) - i)), (int) (20 * hd + j));
                }

                quad("leftLeg", "back", pt(i, j, 0), pt(i + 1, j, 0),
                        pt(i + 1, j + 1, 0), pt(i, j + 1, 0), color1);
                quad("leftLeg", "front", pt(i, j, 4 * hd), pt(i + 1, j, 4 * hd),
                        pt(i + 1, j + 1, 4 * hd), pt(i, j + 1, 4 * hd), color2);
            }
        }
        for (int j = 0; j < 12 * hd; j++) {
            for (int k = 0; k < 4 * hd; k++) {
                int color1;
                int color2;

                if (isNewSkinType) {
                    color1 = color((int) (16 * hd + k), (int) (52 * hd + j)); // from left to right
                    color2 = color((int) (27 * hd - k), (int) (52 * hd + j)); // from right to left
                } else {
                    color1 = color((int) (0 + ((4 * hd - 1) - k)), (int) (20 * hd + j));
                    color2 = color((int) ((12 * hd - 1) - ((4 * hd - 1) - k)), (int) (20 * hd + j));
                }

                quad("leftLeg", "right", pt(0, j, k), pt(0, j, k + 1),
                        pt(0, j + 1, k + 1), pt(0, j + 1, k), color1);
                quad("leftLeg", "left", pt(4 * hd, j, k), pt(4 * hd, j, k + 1),
                        pt(4 * hd, j + 1, k + 1), pt(4 * hd, j + 1, k), color2);
            }
        }
        for (int i = 0; i < 4 * hd; i++) {
            for (int k = 0; k < 4 * hd; k++) {
                int color1;
                int color2;

                if (isNewSkinType) {
                    color1 = color((int) (20 * hd + i), (int) (48 * hd + k)); // from left to right
                    color2 = color((int) (24 * hd + i), (int) (48 * hd + k)); // from left to right
                } else {
                    color1 = color((int) (4 * hd + ((4 * hd - 1) - i)), (int) (16 * hd + k));
                    color2 = color((int) (8 * hd + ((4 * hd - 1) - i)), (int) ((20 * hd - 1) - k));
                }

                quad("leftLeg", "top", pt(i, 0, k), pt(i + 1, 0, k),
                        pt(i + 1, 0, k + 1), pt(i, 0, k + 1), color1);
                quad("leftLeg", "bottom", pt(i, 12 * hd, k), pt(i + 1, 12 * hd, k),
                        pt(i + 1, 12 * hd, k + 1), pt(i, 12 * hd, k + 1), color2);
            }
        }
    }

    // ======================================================== rotation & projection

    private void memberRotation() {
        rotatePart("head", 4, 8, 2);
        rotatePart("helmet", 4, 8, 2);

        if (!headOnly) {
            rotatePart("rightArm", -2, 8, 2);
            rotatePart("leftArm", 10, 8, 2);
            // 腿部 z 偏移：sin_alpha < 0 ? 0 : 4（上游条件保留）
            Angles rl = membersAngles.get("rightLeg");
            rotatePart("rightLeg", 2, 20, rl.sinAlpha() < 0 ? 0 : 4);
            Angles ll = membersAngles.get("leftLeg");
            rotatePart("leftLeg", 6, 20, ll.sinAlpha() < 0 ? 0 : 4);
        }
    }

    private void rotatePart(String part, double dx, double dy, double dz) {
        Angles m = membersAngles.get(part);

        for (List<FacePolygon> facePolys : polygons.get(part).values()) {
            for (FacePolygon poly : facePolys) {
                poly.preProject(dx, dy, dz, m.cosAlpha(), m.sinAlpha(), m.cosOmega(), m.sinOmega());
            }
        }
    }

    private void createProjectionPlan() {
        double ca = cosAlpha;
        double sa = sinAlpha;
        double co = cosOmega;
        double so = sinOmega;

        for (Map<String, List<FacePolygon>> piece : polygons.values()) {
            for (List<FacePolygon> facePolys : piece.values()) {
                for (FacePolygon poly : facePolys) {
                    if (!poly.projected()) {
                        poly.project(ca, sa, co, so, bounds);
                    }
                }
            }
        }
    }

    private GdImage displayImage() {
        double minX = bounds.minX;
        double minY = bounds.minY;
        double width = bounds.maxX - bounds.minX;
        double height = bounds.maxY - bounds.minY;
        double ratio2 = ratio * 2;

        double srcWidthD = ratio2 * width + 1;
        double srcHeightD = ratio2 * height + 1;
        int srcWidth = (int) srcWidthD;
        int srcHeight = (int) srcHeightD;

        GdImage image = GdImage.createEmptyCanvas(srcWidth, srcHeight);

        for (DrawPass pass : getDisplayOrder()) {
            for (String face : pass.faces()) {
                for (FacePolygon poly : polygons.get(pass.piece()).get(face)) {
                    poly.draw(image, minX, minY, ratio2);
                }
            }
        }

        // Anti-aliasing
        double realWidthD = srcWidthD / 2;
        double realHeightD = srcHeightD / 2;
        int realWidth = (int) realWidthD;
        int realHeight = (int) realHeightD;
        GdImage destImage = GdImage.createEmptyCanvas(realWidth, realHeight);
        destImage.resampleFrom(image, 0, 0, realWidth, realHeight);

        return destImage;
    }

    private Set<String> visibleFront(String part) {
        return visibleFaces.get(part).get("front");
    }

    /** {@code getDisplayOrder()}（分支与顺序逐条对齐源码）。 */
    private List<DrawPass> getDisplayOrder() {
        List<DrawPass> displayOrder = new ArrayList<>();

        if (frontFaces.contains("top")) {
            if (frontFaces.contains("right")) {
                displayOrder.add(new DrawPass("leftLeg", backFaces));
                displayOrder.add(new DrawPass("leftLeg", visibleFront("leftLeg")));
                displayOrder.add(new DrawPass("rightLeg", backFaces));
                displayOrder.add(new DrawPass("rightLeg", visibleFront("rightLeg")));
                displayOrder.add(new DrawPass("leftArm", backFaces));
                displayOrder.add(new DrawPass("leftArm", visibleFront("leftArm")));
                displayOrder.add(new DrawPass("torso", backFaces));
                displayOrder.add(new DrawPass("torso", visibleFront("torso")));
                displayOrder.add(new DrawPass("rightArm", backFaces));
                displayOrder.add(new DrawPass("rightArm", visibleFront("rightArm")));
            } else {
                displayOrder.add(new DrawPass("rightLeg", backFaces));
                displayOrder.add(new DrawPass("rightLeg", visibleFront("rightLeg")));
                displayOrder.add(new DrawPass("leftLeg", backFaces));
                displayOrder.add(new DrawPass("leftLeg", visibleFront("leftLeg")));
                displayOrder.add(new DrawPass("rightArm", backFaces));
                displayOrder.add(new DrawPass("rightArm", visibleFront("rightArm")));
                displayOrder.add(new DrawPass("torso", backFaces));
                displayOrder.add(new DrawPass("torso", visibleFront("torso")));
                displayOrder.add(new DrawPass("leftArm", backFaces));
                displayOrder.add(new DrawPass("leftArm", visibleFront("leftArm")));
            }

            displayOrder.add(new DrawPass("helmet", backFaces));
            displayOrder.add(new DrawPass("head", backFaces));
            displayOrder.add(new DrawPass("head", visibleFront("head")));
            displayOrder.add(new DrawPass("helmet", visibleFront("head")));
        } else {
            displayOrder.add(new DrawPass("helmet", backFaces));
            displayOrder.add(new DrawPass("head", backFaces));
            displayOrder.add(new DrawPass("head", visibleFront("head")));
            displayOrder.add(new DrawPass("helmet", visibleFront("head")));

            if (frontFaces.contains("right")) {
                displayOrder.add(new DrawPass("leftArm", backFaces));
                displayOrder.add(new DrawPass("leftArm", visibleFront("leftArm")));
                displayOrder.add(new DrawPass("torso", backFaces));
                displayOrder.add(new DrawPass("torso", visibleFront("torso")));
                displayOrder.add(new DrawPass("rightArm", backFaces));
                displayOrder.add(new DrawPass("rightArm", visibleFront("rightArm")));
                displayOrder.add(new DrawPass("leftLeg", backFaces));
                displayOrder.add(new DrawPass("leftLeg", visibleFront("leftLeg")));
                displayOrder.add(new DrawPass("rightLeg", backFaces));
                displayOrder.add(new DrawPass("rightLeg", visibleFront("rightLeg")));
            } else {
                displayOrder.add(new DrawPass("rightArm", backFaces));
                displayOrder.add(new DrawPass("rightArm", visibleFront("rightArm")));
                displayOrder.add(new DrawPass("torso", backFaces));
                displayOrder.add(new DrawPass("torso", visibleFront("torso")));
                displayOrder.add(new DrawPass("leftArm", backFaces));
                displayOrder.add(new DrawPass("leftArm", visibleFront("leftArm")));
                displayOrder.add(new DrawPass("rightLeg", backFaces));
                displayOrder.add(new DrawPass("rightLeg", visibleFront("rightLeg")));
                displayOrder.add(new DrawPass("leftLeg", backFaces));
                displayOrder.add(new DrawPass("leftLeg", visibleFront("leftLeg")));
            }
        }

        return displayOrder;
    }
}
