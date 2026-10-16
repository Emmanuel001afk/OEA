package com.oea.launcher.privilege.adb;

import java.math.BigInteger;

/**
 * Edwards25519 point arithmetic for the SPAKE2 exchange of Android's wireless
 * debugging pairing (BoringSSL {@code spake25519.cc} runs on this curve).
 *
 * Only standard {@link BigInteger} math is used, so this class runs identically
 * on the JVM and on Android with no security-provider dependencies. Point
 * operations use extended homogeneous coordinates (X:Y:Z:T) with the standard
 * a = -1 twisted Edwards addition law, so a full scalar multiplication needs no
 * per-step modular inversion.
 *
 * This runs only while pairing: a handful of scalar multiplications per second
 * at worst, far below anything a phone would notice.
 */
final class Ed25519 {
    private Ed25519() {}

    /** Field prime: 2^255 - 19. */
    static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    /** Order of the prime-order subgroup. */
    static final BigInteger L = BigInteger.TWO.pow(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    /** Curve constant d = -121665/121666 mod p. */
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);

    /** A point in extended coordinates; (0,1,1,0) is the neutral element. */
    static final class Point {
        final BigInteger x, y, z, t;
        static final Point IDENTITY = new Point(
                BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x; this.y = y; this.z = z; this.t = t;
        }

        /** 32-byte compressed encoding: little-endian y with the x sign in bit 255. */
        byte[] encode() {
            BigInteger zInv = z.modInverse(P);
            BigInteger ax = x.multiply(zInv).mod(P);
            BigInteger ay = y.multiply(zInv).mod(P);
            byte[] le = toLittle32(ay);
            if (ax.testBit(0)) le[31] |= (byte) 0x80;
            return le;
        }
    }

    /** The standard Ed25519 base point (y = 4/5, x even). */
    static final Point BASE = basePoint();

    private static Point basePoint() {
        BigInteger y = BigInteger.valueOf(4)
                .multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger x = recoverX(y, false);
        return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    private static BigInteger recoverX(BigInteger y, boolean sign) {
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        BigInteger xx = u.multiply(v.modInverse(P)).mod(P);
        BigInteger x = xx.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P);
        if (!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) {
            x = x.multiply(SQRT_M1).mod(P);
        }
        if (!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) {
            throw new ArithmeticException("point is not on the curve");
        }
        if (x.testBit(0) != sign) x = P.subtract(x).mod(P);
        return x;
    }

    /** sqrt(-1) mod p. */
    private static final BigInteger SQRT_M1 = BigInteger.TWO
            .modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P);

    /** Extended-coordinate addition (twisted Edwards, a = -1, unrolled). */
    static Point add(Point p, Point q) {
        BigInteger a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P);
        BigInteger b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P);
        BigInteger c = BigInteger.TWO.multiply(p.t).multiply(q.t).multiply(D).mod(P);
        BigInteger d = BigInteger.TWO.multiply(p.z).multiply(q.z).mod(P);
        BigInteger e = b.subtract(a).mod(P);
        BigInteger f = d.subtract(c).mod(P);
        BigInteger g = d.add(c).mod(P);
        BigInteger h = b.add(a).mod(P);
        return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P),
                f.multiply(g).mod(P), e.multiply(h).mod(P));
    }

    /**
     * Point doubling, done through the same unified addition formula (the
     * extended add-2008-hwcd-3 law is complete for this curve, so p + p is
     * exact). Keeping one addition law means the doubling result was verified
     * against the affine reference implementation together with everything
     * else, instead of trusting a second hand-rolled formula set.
     */
    static Point dbl(Point p) {
        return add(p, p);
    }

    /** Scalar multiplication, plain double-and-add over the scalar's bits. */
    static Point multiply(Point p, BigInteger k) {
        Point r = Point.IDENTITY;
        Point base = p;
        int bits = k.bitLength();
        for (int i = 0; i < bits; i++) {
            if (k.testBit(i)) r = add(r, base);
            base = dbl(base);
        }
        return r;
    }

    /**
     * Decodes a 32-byte compressed point and checks it is on the curve.
     * Returns null for any invalid input (wrong length, y out of range, or no
     * square root).
     */
    static Point decode(byte[] in) {
        if (in == null || in.length != 32) return null;
        byte[] copy = in.clone();
        boolean sign = (copy[31] & 0x80) != 0;
        copy[31] &= 0x7F;
        BigInteger y = BigInteger.ZERO;
        for (int i = 31; i >= 0; i--) {
            y = y.shiftLeft(8).or(BigInteger.valueOf(copy[i] & 0xFF));
        }
        if (y.compareTo(P) >= 0) return null;
        BigInteger x;
        try {
            x = recoverX(y, sign);
        } catch (ArithmeticException e) {
            return null;
        }
        return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    /** Reduces a 32-byte little-endian value modulo the subgroup order. */
    static BigInteger scReduce(byte[] le32) {
        BigInteger v = fromLittle(le32);
        return v.mod(L);
    }

    static BigInteger fromLittle(byte[] le) {
        BigInteger v = BigInteger.ZERO;
        for (int i = le.length - 1; i >= 0; i--) {
            v = v.shiftLeft(8).or(BigInteger.valueOf(le[i] & 0xFF));
        }
        return v;
    }

    /** Little-endian bytes of a value, truncated/zero-padded to exactly 32 bytes. */
    static byte[] toLittle32(BigInteger v) {
        byte[] out = new byte[32];
        byte[] mag = v.toByteArray();
        int n = Math.min(32, mag.length);
        for (int i = 0; i < n; i++) out[i] = mag[mag.length - 1 - i];
        return out;
    }
}
