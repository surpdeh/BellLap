export interface Env {
  REPORTS: R2Bucket;
  INSTALL_LIMITER: RateLimit;
  IP_LIMITER: RateLimit;

  GITHUB_OWNER: string;
  GITHUB_REPO: string;
  R2_BUCKET_NAME: string;
  CF_ACCOUNT_ID: string;
  FIREBASE_PROJECT_NUMBER: string;
  FIREBASE_APP_ID: string;

  GITHUB_APP_ID?: string;
  GITHUB_INSTALLATION_ID?: string;
  GITHUB_APP_PRIVATE_KEY?: string;
  R2_ACCESS_KEY_ID?: string;
  R2_SECRET_ACCESS_KEY?: string;
}

/** Env with every required value checked present and non-empty. */
export interface Config {
  reports: R2Bucket;
  installLimiter: RateLimit;
  ipLimiter: RateLimit;
  github: { owner: string; repo: string; appId: string; installationId: string; privateKey: string };
  r2: { accountId: string; bucket: string; accessKeyId: string; secretAccessKey: string };
  firebase: { projectNumber: string; appId: string };
}

const nonEmpty = (v: string | undefined): v is string => typeof v === 'string' && v.trim() !== '';

export function loadConfig(env: Partial<Env>): Config | null {
  const {
    REPORTS, INSTALL_LIMITER, IP_LIMITER,
    GITHUB_OWNER, GITHUB_REPO, GITHUB_APP_ID, GITHUB_INSTALLATION_ID, GITHUB_APP_PRIVATE_KEY,
    CF_ACCOUNT_ID, R2_BUCKET_NAME, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY,
    FIREBASE_PROJECT_NUMBER, FIREBASE_APP_ID,
  } = env;
  if (!REPORTS || !INSTALL_LIMITER || !IP_LIMITER) return null;
  if (
    !nonEmpty(GITHUB_OWNER) || !nonEmpty(GITHUB_REPO) || !nonEmpty(GITHUB_APP_ID) ||
    !nonEmpty(GITHUB_INSTALLATION_ID) || !nonEmpty(GITHUB_APP_PRIVATE_KEY) ||
    !nonEmpty(CF_ACCOUNT_ID) || !nonEmpty(R2_BUCKET_NAME) || !nonEmpty(R2_ACCESS_KEY_ID) ||
    !nonEmpty(R2_SECRET_ACCESS_KEY) || !nonEmpty(FIREBASE_PROJECT_NUMBER) || !nonEmpty(FIREBASE_APP_ID)
  ) {
    return null;
  }
  return {
    reports: REPORTS,
    installLimiter: INSTALL_LIMITER,
    ipLimiter: IP_LIMITER,
    github: {
      owner: GITHUB_OWNER,
      repo: GITHUB_REPO,
      appId: GITHUB_APP_ID,
      installationId: GITHUB_INSTALLATION_ID,
      privateKey: GITHUB_APP_PRIVATE_KEY,
    },
    r2: { accountId: CF_ACCOUNT_ID, bucket: R2_BUCKET_NAME, accessKeyId: R2_ACCESS_KEY_ID, secretAccessKey: R2_SECRET_ACCESS_KEY },
    firebase: { projectNumber: FIREBASE_PROJECT_NUMBER, appId: FIREBASE_APP_ID },
  };
}
