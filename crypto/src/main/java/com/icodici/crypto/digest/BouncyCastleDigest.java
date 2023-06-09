package com.icodici.crypto.digest;

/**
 * Any digest implementation based on the BouncyCastle cryptography backend.
 */
public abstract class BouncyCastleDigest extends Digest {

    protected abstract org.bouncycastle.crypto.Digest getUnderlyingDigest();

    @Override
    protected void _update(byte[] data, int offset, int size) {
        getUnderlyingDigest().update(data, offset, size);
    }

    @Override
    protected byte[] _digest() {
        final org.bouncycastle.crypto.Digest md = getUnderlyingDigest();
        final byte[] result = new byte[md.getDigestSize()];
        md.doFinal(result, 0);
        return result;
    }

    @Override
    public int getLength() {
        return getUnderlyingDigest().getDigestSize();
    }
}
