use picsou::crypto::CryptoEncryption;

const KEY_256: &str = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
const KEY_128: &str = "AAECAwQFBgcICQoLDA0ODw==";

/// Produced by the Java com.picsou.config.CryptoEncryption (backend 1.1.0 jar, jshell).
const JAVA_VECTORS: &[(&str, &str, &str)] = &[
    (
        KEY_256,
        "kf53oQ3hKvpUcrofxHGr6579ToaBpCx5KRmuw+z/e8qNpBHK5ai82lwZK2U=",
        "JBSWY3DPEHPK3PXP",
    ),
    (
        KEY_256,
        "nN5feLYczNso5z+QE4sweV24Cgv2byiBSgfij/QVEriO60Ixy1E3vyIUCoKKFOtN9luiT0x2",
        "picsou-vector-é€-漢字",
    ),
    (
        KEY_128,
        "inurfYo+7S4YfkNBuy+3QKWeiBsx/5r8vj9tja5tTvQtzNRHmXq7gyx0",
        "aes-128 vector",
    ),
];

#[test]
fn decrypts_what_java_encrypted() {
    for (key, cipher, plain) in JAVA_VECTORS {
        assert_eq!(
            CryptoEncryption::new(key).unwrap().decrypt(cipher).unwrap(),
            *plain
        );
    }
}

#[test]
fn round_trips_with_a_fresh_iv_each_time() {
    let crypto = CryptoEncryption::new(KEY_256).unwrap();
    let a = crypto.encrypt("secret").unwrap();
    let b = crypto.encrypt("secret").unwrap();
    assert_ne!(a, b);
    assert_eq!(crypto.decrypt(&a).unwrap(), "secret");
}

#[test]
fn rejects_bad_keys_and_tampered_text() {
    assert!(CryptoEncryption::new("").is_err());
    assert!(CryptoEncryption::new("not base64!").is_err());
    assert!(CryptoEncryption::new("AAEC").is_err());
    let crypto = CryptoEncryption::new(KEY_256).unwrap();
    let mut tampered = crypto.encrypt("secret").unwrap().into_bytes();
    tampered[20] = if tampered[20] == b'A' { b'B' } else { b'A' };
    assert!(
        crypto
            .decrypt(std::str::from_utf8(&tampered).unwrap())
            .is_err()
    );
}
