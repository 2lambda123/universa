/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>, August 2017.
 *
 */

package com.icodici.crypto.digest;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA3Digest;

/**
 * SHA3-256 (SHA-3 family) digest implementation.
 */
public class Sha3_384 extends BouncyCastleDigest {

    final Digest md = new SHA3Digest(384);

    public Sha3_384() {
    }

    @Override
    protected int getChunkSize() {
        return 104;
    }

    @Override
    protected Digest getUnderlyingDigest() {
        return md;
    }
}
