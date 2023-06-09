/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>, August 2017.
 *
 */

package com.icodici.crypto.digest;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.GOST3411_2012_256Digest;

/**
 * GOST R 34.11-2012, 256 variation “Streebog” (GOST family) digest implementation.
 */
public class Gost3411_2012_256 extends BouncyCastleDigest {

    final Digest md = new GOST3411_2012_256Digest();

    public Gost3411_2012_256() {
    }

    @Override
    protected int getChunkSize() {
        return 64;
    }

    @Override
    protected Digest getUnderlyingDigest() {
        return md;
    }
}
