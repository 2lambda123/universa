/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>, August 2017.
 *
 */

package com.icodici.crypto;

import com.icodici.crypto.digest.*;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA3Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Enumeration with various supported hash functions.
 * <p>
 * Created by sergeych on 07/12/16.
 */
public enum HashType {
    SHA1,
    SHA256,
    SHA512,
    SHA3_256,
    SHA3_384,
    SHA3_512;
    /* When adding any new value, make sure to add the line in {@link algorithmByType}! */

    private static Map algorithmByType = Collections.unmodifiableMap(new HashMap<HashType, Supplier<Digest>>() {{
        put(HashType.SHA1, () -> new SHA1Digest());
        put(HashType.SHA256, () -> new SHA256Digest());
        put(HashType.SHA512, () -> new SHA512Digest());
        put(HashType.SHA3_256, () -> new SHA3Digest(256));
        put(HashType.SHA3_384, () -> new SHA3Digest(384));
        put(HashType.SHA3_512, () -> new SHA3Digest(512));
    }});


    private static Map algorithmNameByType = Collections.unmodifiableMap(new HashMap<HashType, String>() {{
        for (Object key : algorithmByType.keySet()) {
            put((HashType) key, ((Supplier<Digest>) algorithmByType.get(key)).get().getAlgorithmName());
        }
    }});
    private static Map algorithmTypeByName = Collections.unmodifiableMap(new HashMap<String, HashType>() {{
        for (Object key : algorithmByType.keySet()) {
            put(((Supplier<Digest>) algorithmByType.get(key)).get().getAlgorithmName(), (HashType) key);
        }
    }});

    /**
     * Create a new __bouncycastle_ standard {@link Digest} for this hash type. To get an Universa digest instead,
     * use {@link #createDigest()} instead.
     */
    public Digest makeDigest() {
        return ((Supplier<Digest>) this.algorithmByType.get(this)).get();
    }

    /**
     * Get the name of the algorithm.
     */
    public String getAlgorithmName() {
        return (String) algorithmNameByType.get(this);
    }

    /**
     * Given the digest/hash algorithm name, return a new instance of the appropriate {@link HashType}.
     * May return `null` if the digest `algorithmName` is not supported.
     */
    public static HashType getByAlgorithmName(String algorithmName) {
        HashType hashTypeClass = (HashType) algorithmTypeByName.get(algorithmName);
        if (hashTypeClass == null) {
            return null;
        } else {
            return hashTypeClass;
        }

    }

    /**
     * Create unicrypto type digest for a given constant
     * @return instance of the universa digest that implements this algorithm
     */
    public com.icodici.crypto.digest.Digest createDigest() {
        return new GenericBCDigest(makeDigest());
    }

    /**
     * Get a unicrypto class object for a hash type
     * @return universa digest class that (if instantiated) implements this hash algorithm
     */
    public Class<? extends com.icodici.crypto.digest.Digest> findDigestClass() {
        switch (this) {
            case SHA1:
                return Sha1.class;
            case SHA256:
                return Sha256.class;
            case SHA512:
                return Sha512.class;
            case SHA3_256:
                return Sha3_256.class;
            case SHA3_384:
                return Sha3_384.class;
            default:
                break;
        }
        throw new IllegalArgumentException("this hash type is not supported for the findDogestClass() operation");
    }
}
