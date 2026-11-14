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
/**
 * A single-use server challenge pre-issued inside a signed operation response, so the
 * client can sign its next request without a separate POST /v1/challenges round trip.
 * `processNonce` is present only when the challenge is bound to one process; a missing
 * value means the challenge is "floating" and may be consumed by any process of the
 * same credential.
 */
export interface ChallengeTicket {
  challengeId: string;
  serverNonce: string;
  expiresAt: number;
  method: "POST" | "GET";
  path: string;
  processNonce?: string;
}
export interface InitializationResponse {
  initializationId: string;
  grantId: string;
  grantExpiresAt: number;
  payloadId: string;
  manifest: ManifestEnvelope;
  contentKey: string;
  downloadPath: string;
  /** Optional pipelined challenges; absent for clients that did not ask for them. */
  challenges?: ChallengeTicket[];
}
