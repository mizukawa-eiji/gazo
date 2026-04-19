package com.example.gazo.vault;

/**
 * WebDAV 同期（初回ダウンロード・flush 時のアップロード等）の進捗。
 *
 * @param completed 完了した単位作業数
 * @param total       見積もり総数（0 のときはバー非表示にできる）
 * @param phase       表示用のフェーズ名（例: 取得、アップロード、削除）
 * @param path        現在処理中の相対パス（省略可）
 */
public record WebDavSyncProgress(int completed, int total, String phase, String path) {}
