/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.spi.encryption;

import java.io.Serializable;
import org.apache.ignite.IgniteException;
import org.apache.ignite.encryption.EncryptionSpi;
import org.apache.ignite.spi.IgniteSpiAdapter;
import org.apache.ignite.spi.IgniteSpiException;
import org.apache.ignite.spi.IgniteSpiNoop;
import org.apache.ignite.spi.encryption.aes.AESEncryptionSpi;
import org.jetbrains.annotations.Nullable;

/**
 * No operation {@code EncryptionSPI} implementation.
 *
 * @see EncryptionSpi
 * @see AESEncryptionSpi
 */
@IgniteSpiNoop
public class NoopEncryptionSpi extends IgniteSpiAdapter implements EncryptionSpi {
    /** {@inheritDoc} */
    @Override public byte[] masterKeyDigest() {
        return new byte[0];
    }

    /** {@inheritDoc} */
    @Override public Serializable create() throws IgniteException {
        throw new IgniteSpiException("You have to configure custom EncryptionSpi implementation.");
    }

    /** {@inheritDoc} */
    @Override public byte[] encrypt(byte[] data, Serializable key, int start, int length) {
        throw new IgniteSpiException("You have to configure custom EncryptionSpi implementation.");
    }

    /** {@inheritDoc} */
    @Override public byte[] encryptNoPadding(byte[] data, Serializable key, int start, int length) {
        throw new IgniteSpiException("You have to configure custom EncryptionSpi implementation.");
    }

    /** {@inheritDoc} */
    @Override public byte[] decrypt(byte[] data, Serializable key) {
        throw new IgniteSpiException("You have to configure custom EncryptionSpi implementation.");
    }

    /** {@inheritDoc} */
    @Override public byte[] decryptNoPadding(byte[] data, Serializable key) {
        throw new IgniteSpiException("You have to configure custom EncryptionSpi implementation.");
    }

    /** {@inheritDoc} */
    @Override public byte[] encryptKey(Serializable key) {
        throw new IgniteSpiException("You have to configure custom EncryptionSpi implementation.");
    }

    /** {@inheritDoc} */
    @Override public Serializable decryptKey(byte[] key) {
        throw new IgniteSpiException("You have to configure custom EncryptionSpi implementation.");
    }

    /** {@inheritDoc} */
    @Override public int encryptedSize(int dataSize) {
        return dataSize;
    }

    /** {@inheritDoc} */
    @Override public int encryptedSizeNoPadding(int dataSize) {
        return dataSize;
    }

    /** {@inheritDoc} */
    @Override public void spiStart(@Nullable String igniteInstanceName) throws IgniteSpiException {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void spiStop() throws IgniteSpiException {
        // No-op.
    }
}
