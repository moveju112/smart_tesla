package com.wemade.teslable.crypto

import java.math.BigInteger
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint

/**
 * P-256 점 연산 최소 구현.
 *
 * JCA에는 "스칼라로 공개키 복원"에 해당하는 공개 API가 없다.
 * 소프트웨어 키(API 30 이하) 복원에만 쓰이며, 비밀 값에 대한 상수시간 보장이 없다.
 *
 * ponytail: 상수시간 아님. API 31+ Keystore 경로에서는 호출되지 않는다.
 *           구형 기기까지 보안 등급을 맞춰야 하면 BouncyCastle로 교체한다.
 */
internal object EcMath {

    /** G * scalar */
    fun multiplyGenerator(params: ECParameterSpec, scalar: BigInteger): ECPoint {
        val p = (params.curve.field as ECFieldFp).p
        val a = params.curve.a
        var result: ECPoint? = null            // 무한원점
        var addend = params.generator
        var k = scalar

        // 이진 전개로 double-and-add
        while (k.signum() > 0) {
            if (k.testBit(0)) result = add(result, addend, p, a)
            addend = add(addend, addend, p, a)
            k = k.shiftRight(1)
        }
        return requireNotNull(result) { "스칼라가 0이라 공개키를 만들 수 없다" }
    }

    fun encodePoint(point: ECPoint): ByteArray {
        val out = ByteArray(65)
        out[0] = 0x04
        pad32(point.affineX).copyInto(out, 1)
        pad32(point.affineY).copyInto(out, 33)
        return out
    }

    private fun add(p1: ECPoint?, p2: ECPoint, p: BigInteger, a: BigInteger): ECPoint {
        if (p1 == null) return p2
        if (p1 == ECPoint.POINT_INFINITY) return p2

        val slope = if (p1.affineX == p2.affineX && p1.affineY == p2.affineY) {
            // 배점: (3x^2 + a) / 2y
            val numerator = p1.affineX.multiply(p1.affineX).multiply(THREE).add(a).mod(p)
            val denominator = p1.affineY.multiply(TWO).modInverse(p)
            numerator.multiply(denominator).mod(p)
        } else {
            // 덧셈: (y2 - y1) / (x2 - x1)
            val numerator = p2.affineY.subtract(p1.affineY).mod(p)
            val denominator = p2.affineX.subtract(p1.affineX).modInverse(p)
            numerator.multiply(denominator).mod(p)
        }

        val x3 = slope.multiply(slope).subtract(p1.affineX).subtract(p2.affineX).mod(p)
        val y3 = slope.multiply(p1.affineX.subtract(x3)).subtract(p1.affineY).mod(p)
        return ECPoint(x3, y3)
    }

    private fun pad32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32).also { raw.copyInto(it, 32 - raw.size) }
        }
    }

    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
}
