import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from 'jose';
import type { Config } from './env';

export const APP_CHECK_JWKS_URL = 'https://firebaseappcheck.googleapis.com/v1/jwks';

let remoteJwks: JWTVerifyGetKey | undefined;
const appCheckJwks = (): JWTVerifyGetKey => (remoteJwks ??= createRemoteJWKSet(new URL(APP_CHECK_JWKS_URL)));

/** Verifies a Firebase App Check token. Any failure, including a JWKS fetch error, counts as not verified. */
export async function verifyAppCheck(
  token: string | null,
  firebase: Config['firebase'],
  keys: JWTVerifyGetKey = appCheckJwks(),
): Promise<boolean> {
  if (!token) return false;
  try {
    const { payload } = await jwtVerify(token, keys, {
      algorithms: ['RS256'],
      typ: 'JWT',
      issuer: `https://firebaseappcheck.googleapis.com/${firebase.projectNumber}`,
      audience: `projects/${firebase.projectNumber}`,
    });
    return payload.sub === firebase.appId;
  } catch {
    return false;
  }
}
