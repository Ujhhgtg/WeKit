export interface ManifestEnvelope {
  keyId: string;
  manifest: string;
  signature: string;
}
export interface ReleaseManifest {
  payloadId: string;
  businessVersion: string;
  sourceCommit: string;
  entryClass: string;
  apkSha256: string;
  apkSize: number;
  cipherSha256: string;
  cipherSize: number;
  encryption: {
    algorithm: "AES-256-GCM";
    chunkSize: 1048576;
    noncePrefix: string;
    chunkCount: number;
  };
  createdAt: string;
}
export interface RegistrationChallengeRequest {
  mode: "NEW_KEY" | "REGISTERED_KEY" | "UNATTESTED_KEY";
  tokenSha256: string;
  registrationNonce: string;
  existingInstallationId?: string;
}
export interface ChallengeRequest {
  registrationId?: string;
  installationId?: string;
  method: "POST" | "GET";
  path: string;
  processNonce: string;
  grantId?: string;
}
export interface InitializationResponse {
  initializationId: string;
  grantId: string;
  grantExpiresAt: number;
  payloadId: string;
  manifest: ManifestEnvelope;
  contentKey: string;
  downloadPath: string;
}
