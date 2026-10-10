package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;

/** Normalized immutable fractions shared by the exact simplex solvers. */
final class ExactRational implements Comparable<ExactRational> {
    static final ExactRational ZERO = new ExactRational(BigInteger.ZERO, BigInteger.ONE);
    static final ExactRational ONE = new ExactRational(BigInteger.ONE, BigInteger.ONE);
    static final ExactRational NEGATIVE_ONE = new ExactRational(BigInteger.ONE.negate(), BigInteger.ONE);

    private final BigInteger numerator;
    private final BigInteger denominator;

    ExactRational(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() == 0) {
            throw new ArithmeticException("zero denominator");
        }
        // Sparse simplex rows contain mostly zero and integer cells.
        if (numerator.signum() == 0) {
            this.numerator = BigInteger.ZERO;
            this.denominator = BigInteger.ONE;
            return;
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        if (denominator.equals(BigInteger.ONE)) {
            this.numerator = numerator;
            this.denominator = BigInteger.ONE;
            return;
        }
        BigInteger gcd = numerator.gcd(denominator);
        this.numerator = gcd.equals(BigInteger.ONE) ? numerator : numerator.divide(gcd);
        this.denominator = gcd.equals(BigInteger.ONE) ? denominator : denominator.divide(gcd);
    }

    static ExactRational of(BigInteger value) {
        if (value.signum() == 0) {
            return ZERO;
        }
        if (value.equals(BigInteger.ONE)) {
            return ONE;
        }
        if (value.equals(NEGATIVE_ONE.numerator)) {
            return NEGATIVE_ONE;
        }
        return new ExactRational(value, BigInteger.ONE);
    }

    ExactRational add(ExactRational other) {
        if (other.signum() == 0) return this;
        if (signum() == 0) return other;
        if (denominator.equals(other.denominator)) {
            return new ExactRational(numerator.add(other.numerator), denominator);
        }
        return new ExactRational(
                numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    ExactRational subtract(ExactRational other) {
        if (other.signum() == 0) return this;
        if (equals(other)) return ZERO;
        if (denominator.equals(other.denominator)) {
            return new ExactRational(numerator.subtract(other.numerator), denominator);
        }
        return new ExactRational(
                numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    ExactRational multiply(ExactRational other) {
        if (signum() == 0 || other.signum() == 0) return ZERO;
        if (equals(ONE)) return other;
        if (other.equals(ONE)) return this;
        return new ExactRational(
                numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    ExactRational divide(ExactRational other) {
        if (other.signum() == 0) throw new ArithmeticException("zero denominator");
        if (signum() == 0) return ZERO;
        if (other.equals(ONE)) return this;
        if (equals(other)) return ONE;
        return new ExactRational(
                numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }

    BigInteger numerator() { return numerator; }
    BigInteger denominator() { return denominator; }

    int signum() {
        return numerator.signum();
    }

    boolean isInteger() {
        return denominator.equals(BigInteger.ONE);
    }

    BigInteger toBigIntegerExact() {
        if (!isInteger()) {
            throw new ArithmeticException("fractional value");
        }
        return numerator;
    }

    BigInteger floor() {
        if (isInteger()) return numerator;
        BigInteger[] divided = numerator.divideAndRemainder(denominator);
        if (numerator.signum() < 0 && divided[1].signum() != 0) {
            return divided[0].subtract(BigInteger.ONE);
        }
        return divided[0];
    }

    @Override
    public int compareTo(ExactRational other) {
        if (denominator.equals(other.denominator)) return numerator.compareTo(other.numerator);
        return numerator.multiply(other.denominator)
                .compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExactRational rational
                && numerator.equals(rational.numerator)
                && denominator.equals(rational.denominator);
    }

    @Override
    public int hashCode() {
        return 31 * numerator.hashCode() + denominator.hashCode();
    }
}
