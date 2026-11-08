import * as asn1 from "asn1js";
import {
  AttributeTypeAndValue,
  Certificate,
  CryptoEngine,
  Extension,
  setEngine,
} from "pkijs";
import { b64, unb64 } from "../src/crypto.ts";
// Entirely local test CA. The production refresh only downloads Google roots;
// this generated trust anchor is inserted into the isolated test DB explicitly.
export async function hardwareClaimFixture(
  target: CryptoKey,
  challenge: string,
  options: {
    securityLevel?: 0 | 1 | 2;
    keySecurityLevel?: 0 | 1 | 2;
    softwareEnforced?: boolean;
    omitRootOfTrust?: boolean;
    omitKeyOrigin?: boolean;
    digest?: number;
    verifiedBootState?: number;
    attestationVersion?: number;
    invalidCertificate?: boolean;
    provisioning?: "FACTORY" | "RKP";
    certifiedSecurityLevel?: "TEE" | "StrongBox";
    notBefore?: Date;
    notAfter?: Date;
  } = {},
) {
  setEngine(
    "test",
    crypto,
    new CryptoEngine({ name: "test", crypto, subtle: crypto.subtle }),
  );
  const rkp = options.provisioning === "RKP";
  const chainLength = rkp ? 5 : 4;
  const keys = await Promise.all(
    Array.from({ length: chainLength - 1 }, () =>
      crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, [
        "sign",
        "verify",
      ]),
    ),
  );
  const certificates: Certificate[] = [];
  for (let i = 0; i < chainLength; i++) {
    const c = new Certificate();
    c.version = 2;
    c.serialNumber = new asn1.Integer({ value: i + 1 });
    const attributes =
      rkp && i === 1
        ? [
            new AttributeTypeAndValue({
              type: "2.5.4.3",
              value: new asn1.Utf8String({ value: "Droid CA2" }),
            }),
            new AttributeTypeAndValue({
              type: "2.5.4.10",
              value: new asn1.Utf8String({ value: "Google LLC" }),
            }),
          ]
        : rkp && i === chainLength - 2
          ? [
              new AttributeTypeAndValue({
                type: "2.5.4.10",
                value: new asn1.Utf8String({
                  value: options.certifiedSecurityLevel ?? "TEE",
                }),
              }),
            ]
          : i < 2
            ? [
                new AttributeTypeAndValue({
                  type: "2.5.4.5",
                  value: new asn1.PrintableString({
                    value: i === 0 ? "test-root" : "test-factory",
                  }),
                }),
              ]
            : [
                new AttributeTypeAndValue({
                  type: "2.5.4.3",
                  value: new asn1.Utf8String({
                    value: i === 2 ? "Test Attester" : "Android Keystore Key",
                  }),
                }),
              ];
    c.subject.typesAndValues = attributes;
    c.issuer.typesAndValues =
      i === 0 ? attributes : certificates[i - 1]!.subject.typesAndValues;
    c.notBefore.value = new Date(Date.now() - 60000);
    c.notAfter.value = new Date(Date.now() + 86400000);
    if (i > 0 && i < chainLength - 1) {
      if (options.notBefore) c.notBefore.value = options.notBefore;
      if (options.notAfter) c.notAfter.value = options.notAfter;
    }
    await c.subjectPublicKeyInfo.importKey(
      i === chainLength - 1 ? target : keys[i]!.publicKey,
    );
    if (i === chainLength - 1) {
      const tag = (n: number, value: asn1.BaseBlock) =>
        new asn1.Constructed({
          idBlock: { tagClass: 3, tagNumber: n },
          value: [value],
        });
      const int = (value: number) => new asn1.Integer({ value });
      const authorization = new asn1.Sequence({
        value: [
          tag(1, new asn1.Set({ value: [int(2)] })),
          tag(2, int(3)),
          tag(3, int(256)),
          tag(5, new asn1.Set({ value: [int(options.digest ?? 4)] })),
          tag(10, int(1)),
          tag(503, new asn1.Null()),
          ...(options.omitKeyOrigin ? [] : [tag(702, int(0))]),
          ...(options.omitRootOfTrust
            ? []
            : [
                tag(
                  704,
                  new asn1.Sequence({
                    value: [
                      new asn1.OctetString({ valueHex: new Uint8Array(32) }),
                      new asn1.Boolean({ value: false }),
                      new asn1.Enumerated({
                        value: options.verifiedBootState ?? 2,
                      }),
                      new asn1.OctetString({ valueHex: new Uint8Array(32) }),
                    ],
                  }),
                ),
              ]),
        ],
      });
      const description = new asn1.Sequence({
        value: [
          int(options.attestationVersion ?? 300),
          new asn1.Enumerated({ value: options.securityLevel ?? 1 }),
          int(3),
          new asn1.Enumerated({
            value: options.keySecurityLevel ?? options.securityLevel ?? 1,
          }),
          new asn1.OctetString({ valueHex: unb64(challenge) }),
          new asn1.OctetString({ valueHex: new Uint8Array() }),
          options.softwareEnforced
            ? authorization
            : new asn1.Sequence({ value: [] }),
          options.softwareEnforced
            ? new asn1.Sequence({ value: [] })
            : authorization,
        ],
      });
      c.extensions = [
        new Extension({
          extnID: "1.3.6.1.4.1.11129.2.1.17",
          extnValue: description.toBER(false),
        }),
      ];
    }
    await c.sign(keys[Math.max(i - 1, 0)]!.privateKey, "SHA-256");
    certificates.push(c);
  }
  return {
    root: b64(certificates[0]!.toSchema().toBER(false)),
    rootSpki: b64(
      certificates[0]!.subjectPublicKeyInfo.toSchema().toBER(false),
    ),
    chain: certificates
      .reverse()
      .map((certificate, index) =>
        options.invalidCertificate && index === 0
          ? b64(new Uint8Array([0x30, 0x80]))
          : b64(certificate.toSchema().toBER(false)),
      ),
  };
}
