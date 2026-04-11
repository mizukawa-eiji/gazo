package com.example.gazo;

/**
 * Vault 解錠のパスワード入力がキャンセルされたときに {@link VaultUnlockFlow#openVaultAsync} の完了コールバックへ渡す。
 */
public final class VaultUnlockCancelledException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}
