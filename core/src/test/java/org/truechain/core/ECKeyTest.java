package org.truechain.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.Random;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;
import org.truechain.address.Address;
import org.truechain.crypto.EncryptedData;
import org.truechain.crypto.KeyCrypter;
import org.truechain.crypto.TransactionSignature;
import org.truechain.network.MainNetParams;
import org.truechain.network.NetworkParameters;
import org.truechain.network.TestNetworkParameters;
import org.truechain.utils.Base16;
import org.truechain.utils.Utils;

public class ECKeyTest {
    private static final Logger log = LoggerFactory.getLogger(ECKeyTest.class);

    private KeyCrypter keyCrypter;

    private static CharSequence PASSWORD1 = "my hovercraft has eels";
    private static CharSequence WRONG_PASSWORD = "it is a snowy day today";

    @Before
    public void setUp() throws Exception {
        SecureRandom secureRandom = new SecureRandom();
    }
    
    @Test
    public void base58Encoding_stress() throws Exception {
        // Replace the loop bound with 1000 to get some keys with leading zero byte
        for (int i = 0 ; i < 20 ; i++) {
            ECKey key = new ECKey();
            ECKey key1 = DumpedPrivateKey.fromBase58(TestNetworkParameters.get(),
                    key.getPrivateKeyEncoded(TestNetworkParameters.get()).toString()).getKey();
            assertEquals(Base16.encode(key.getPrivKeyBytes()),
            		Base16.encode(key1.getPrivKeyBytes()));
        }
    }

    @Test
    public void signTextMessage() throws Exception {
        ECKey key = new ECKey();
        String message = "聡中本";
        String signatureBase64 = key.signMessage(message);
        log.info("Message signed with " + key.toAddress(MainNetParams.get()) + ": " + signatureBase64);
        // Should verify correctly.
        key.verifyMessage(message, signatureBase64);
        try {
            key.verifyMessage("Evil attacker says hello!", signatureBase64);
            fail();
        } catch (SignatureException e) {
            // OK.
        }
    }

    @Test
    public void verifyMessage() throws Exception {
        // Test vector generated by Bitcoin-Qt.
        String message = "hello";
        String sigBase64 = "HxNZdo6ggZ41hd3mM3gfJRqOQPZYcO8z8qdX2BwmpbF11CaOQV+QiZGGQxaYOncKoNW61oRuSMMF8udfK54XqI8=";
        Address expectedAddress = Address.fromBase58(MainNetParams.get(), "14YPSNPi6NSXnUxtPAsyJSuw3pv7AU3Cag");
        ECKey key = ECKey.signedMessageToKey(message, sigBase64);
        Address gotAddress = key.toAddress(MainNetParams.get());
        assertEquals(expectedAddress, gotAddress);
    }

    @Test
    public void keyRecovery() throws Exception {
        ECKey key = new ECKey();
        String message = "Hello World!";
        Sha256Hash hash = Sha256Hash.of(message.getBytes());
        ECKey.ECDSASignature sig = key.sign(hash);
        key = ECKey.fromPublicOnly(key.getPubKeyPoint());
        boolean found = false;
        for (int i = 0; i < 4; i++) {
            ECKey key2 = ECKey.recoverFromSignature(i, sig, hash, true);
            Utils.checkNotNull(key2);
            if (key.equals(key2)) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    @Test
    public void testUnencryptedCreate() throws Exception {
        ECKey key = new ECKey();
        long time = key.getCreationTimeSeconds();
        assertNotEquals(0, time);
        assertTrue(!key.isEncrypted());
        byte[] originalPrivateKeyBytes = key.getPrivKeyBytes();
        ECKey encryptedKey = key.encrypt(keyCrypter, keyCrypter.deriveKey(PASSWORD1));
        assertEquals(time, encryptedKey.getCreationTimeSeconds());
        assertTrue(encryptedKey.isEncrypted());
        assertNull(encryptedKey.getSecretBytes());
        key = encryptedKey.decrypt(keyCrypter.deriveKey(PASSWORD1));
        assertTrue(!key.isEncrypted());
        assertArrayEquals(originalPrivateKeyBytes, key.getPrivKeyBytes());
    }

    @Test
    public void testEncryptedCreate() throws Exception {
        ECKey unencryptedKey = new ECKey();
        byte[] originalPrivateKeyBytes = Utils.checkNotNull(unencryptedKey.getPrivKeyBytes());
        log.info("Original private key = " + Base16.encode(originalPrivateKeyBytes));
        EncryptedData encryptedPrivateKey = keyCrypter.encrypt(unencryptedKey.getPrivKeyBytes(), keyCrypter.deriveKey(PASSWORD1));
        ECKey encryptedKey = ECKey.fromEncrypted(encryptedPrivateKey, keyCrypter, unencryptedKey.getPubKey());
        assertTrue(encryptedKey.isEncrypted());
        assertNull(encryptedKey.getSecretBytes());
        ECKey rebornUnencryptedKey = encryptedKey.decrypt(keyCrypter.deriveKey(PASSWORD1));
        assertTrue(!rebornUnencryptedKey.isEncrypted());
        assertArrayEquals(originalPrivateKeyBytes, rebornUnencryptedKey.getPrivKeyBytes());
    }

    @Test
    public void testEncryptionIsReversible() throws Exception {
        ECKey originalUnencryptedKey = new ECKey();
        EncryptedData encryptedPrivateKey = keyCrypter.encrypt(originalUnencryptedKey.getPrivKeyBytes(), keyCrypter.deriveKey(PASSWORD1));
        ECKey encryptedKey = ECKey.fromEncrypted(encryptedPrivateKey, keyCrypter, originalUnencryptedKey.getPubKey());

        // The key should be encrypted
        assertTrue("Key not encrypted at start",  encryptedKey.isEncrypted());

        // Check that the key can be successfully decrypted back to the original.
        assertTrue("Key encryption is not reversible but it should be", ECKey.encryptionIsReversible(originalUnencryptedKey, encryptedKey, keyCrypter, keyCrypter.deriveKey(PASSWORD1)));

        // Check that key encryption is not reversible if a password other than the original is used to generate the AES key.
        assertTrue("Key encryption is reversible with wrong password", !ECKey.encryptionIsReversible(originalUnencryptedKey, encryptedKey, keyCrypter, keyCrypter.deriveKey(WRONG_PASSWORD)));

        // Change one of the encrypted key bytes (this is to simulate a faulty keyCrypter).
        // Encryption should not be reversible
        byte[] goodEncryptedPrivateKeyBytes = encryptedPrivateKey.encryptedBytes;

        // Break the encrypted private key and check it is broken.
        byte[] badEncryptedPrivateKeyBytes = new byte[goodEncryptedPrivateKeyBytes.length];
        encryptedPrivateKey = new EncryptedData(encryptedPrivateKey.initialisationVector, badEncryptedPrivateKeyBytes);
        ECKey badEncryptedKey = ECKey.fromEncrypted(encryptedPrivateKey, keyCrypter, originalUnencryptedKey.getPubKey());
        assertTrue("Key encryption is reversible with faulty encrypted bytes", !ECKey.encryptionIsReversible(originalUnencryptedKey, badEncryptedKey, keyCrypter, keyCrypter.deriveKey(PASSWORD1)));
    }

    @Test
    public void testToString() throws Exception {
        ECKey key = ECKey.fromPrivate(BigInteger.TEN).decompress(); // An example private key.
        NetworkParameters params = MainNetParams.get();
        assertEquals("ECKey{pub HEX=04a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7893aba425419bc27a3b6c7e693a24c696f794c2ed877a1593cbee53b037368d7, isEncrypted=false, isPubKeyOnly=false}", key.toString());
        assertEquals("ECKey{pub HEX=04a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7893aba425419bc27a3b6c7e693a24c696f794c2ed877a1593cbee53b037368d7, priv HEX=000000000000000000000000000000000000000000000000000000000000000a, priv WIF=5HpHagT65TZzG1PH3CSu63k8DbpvD8s5ip4nEB3kEsreBoNWTw6, isEncrypted=false, isPubKeyOnly=false}", key.toStringWithPrivate(params));
    }

    @Test
    public void testGetPrivateKeyAsHex() throws Exception {
        ECKey key = ECKey.fromPrivate(BigInteger.TEN).decompress(); // An example private key.
        assertEquals("000000000000000000000000000000000000000000000000000000000000000a", key.getPrivateKeyAsHex());
    }

    @Test
    public void testGetPublicKeyAsHex() throws Exception {
        ECKey key = ECKey.fromPrivate(BigInteger.TEN).decompress(); // An example private key.
        assertEquals("04a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7893aba425419bc27a3b6c7e693a24c696f794c2ed877a1593cbee53b037368d7", key.getPublicKeyAsHex());
    }

    @Test
    public void keyRecoveryWithEncryptedKey() throws Exception {
        ECKey unencryptedKey = new ECKey();
        KeyParameter aesKey =  keyCrypter.deriveKey(PASSWORD1);
        ECKey encryptedKey = unencryptedKey.encrypt(keyCrypter, aesKey);

        String message = "Goodbye Jupiter!";
        Sha256Hash hash = Sha256Hash.of(message.getBytes());
        ECKey.ECDSASignature sig = encryptedKey.sign(hash, aesKey);
        unencryptedKey = ECKey.fromPublicOnly(unencryptedKey.getPubKeyPoint());
        boolean found = false;
        for (int i = 0; i < 4; i++) {
            ECKey key2 = ECKey.recoverFromSignature(i, sig, hash, true);
            Utils.checkNotNull(key2);
            if (unencryptedKey.equals(key2)) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    @Test
    public void roundTripDumpedPrivKey() throws Exception {
        ECKey key = new ECKey();
        assertTrue(key.isCompressed());
        NetworkParameters params = TestNetworkParameters.get();
        String base58 = key.getPrivateKeyEncoded(params).toString();
        ECKey key2 = DumpedPrivateKey.fromBase58(params, base58).getKey();
        assertTrue(key2.isCompressed());
        assertTrue(Arrays.equals(key.getPrivKeyBytes(), key2.getPrivKeyBytes()));
        assertTrue(Arrays.equals(key.getPubKey(), key2.getPubKey()));
    }

    @Test
    public void clear() throws Exception {
        ECKey unencryptedKey = new ECKey();
        ECKey encryptedKey = (new ECKey()).encrypt(keyCrypter, keyCrypter.deriveKey(PASSWORD1));

        checkSomeBytesAreNonZero(unencryptedKey.getPrivKeyBytes());

        // The encryptedPrivateKey should be null in an unencrypted ECKey anyhow but check all the same.
        assertTrue(unencryptedKey.getEncryptedPrivateKey() == null);

        checkSomeBytesAreNonZero(encryptedKey.getSecretBytes());
        checkSomeBytesAreNonZero(encryptedKey.getEncryptedPrivateKey().encryptedBytes);
        checkSomeBytesAreNonZero(encryptedKey.getEncryptedPrivateKey().initialisationVector);
    }

    @Test
    public void testCanonicalSigs() throws Exception {
        // Tests the canonical sigs from Bitcoin Core unit tests
        InputStream in = getClass().getResourceAsStream("sig_canonical.json");

        // Poor man's JSON parser (because pulling in a lib for this is overkill)
        while (in.available() > 0) {
            while (in.available() > 0 && in.read() != '"') ;
            if (in.available() < 1)
                break;

            StringBuilder sig = new StringBuilder();
            int c;
            while (in.available() > 0 && (c = in.read()) != '"')
                sig.append((char)c);

            assertTrue(TransactionSignature.isEncodingCanonical(Base16.decode(sig.toString())));
        }
        in.close();
    }

    @Test
    public void testNonCanonicalSigs() throws Exception {
        // Tests the noncanonical sigs from Bitcoin Core unit tests
        InputStream in = getClass().getResourceAsStream("sig_noncanonical.json");

        // Poor man's JSON parser (because pulling in a lib for this is overkill)
        while (in.available() > 0) {
            while (in.available() > 0 && in.read() != '"') ;
            if (in.available() < 1)
                break;

            StringBuilder sig = new StringBuilder();
            int c;
            while (in.available() > 0 && (c = in.read()) != '"')
                sig.append((char)c);

            try {
                final String sigStr = sig.toString();
                assertFalse(TransactionSignature.isEncodingCanonical(Base16.decode(sigStr)));
            } catch (IllegalArgumentException e) {
                // Expected for non-hex strings in the JSON that we should ignore
            }
        }
        in.close();
    }

    @Test
    public void testCreatedSigAndPubkeyAreCanonical() throws Exception {
        // Tests that we will not generate non-canonical pubkeys or signatures
        // We dump failed data to error log because this test is not expected to be deterministic
        ECKey key = new ECKey();
        if (!ECKey.isPubKeyCanonical(key.getPubKey())) {
            log.error(Base16.encode(key.getPubKey()));
            fail();
        }

        byte[] hash = new byte[32];
        new Random().nextBytes(hash);
        byte[] sigBytes = key.sign(Sha256Hash.wrap(hash)).encodeToDER();
        byte[] encodedSig = Arrays.copyOf(sigBytes, sigBytes.length + 1);
        encodedSig[sigBytes.length] = Transaction.SigHash.ALL.byteValue();
        if (!TransactionSignature.isEncodingCanonical(encodedSig)) {
            log.error(Base16.encode(sigBytes));
            fail();
        }
    }

    private static boolean checkSomeBytesAreNonZero(byte[] bytes) {
        if (bytes == null) return false;
        for (byte b : bytes) if (b != 0) return true;
        return false;
    }
}