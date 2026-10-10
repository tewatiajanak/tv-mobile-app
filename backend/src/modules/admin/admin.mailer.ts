import { Inject, Injectable } from '@nestjs/common';
import { APP_CONFIG, AppConfig } from '../../config/app-config';

const RESEND_URL = 'https://api.resend.com/emails';
const TIMEOUT_MS = 10_000;

/** Sends the admin's password reset code by email through Resend's HTTP API. */
@Injectable()
export class AdminMailer {
  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  /** False when no address or API key is configured: reset by email is then unavailable. */
  get configured(): boolean {
    return Boolean(this.config.admin.email && this.config.admin.resendApiKey);
  }

  /** Resolves true when Resend accepted the message. Never throws; the text is never logged. */
  async send(subject: string, text: string): Promise<boolean> {
    const { email, resendApiKey, emailFrom } = this.config.admin;
    if (!email || !resendApiKey) {
      return false;
    }
    try {
      const res = await fetch(RESEND_URL, {
        method: 'POST',
        headers: { Authorization: `Bearer ${resendApiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ from: emailFrom, to: [email], subject, text }),
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      return res.ok;
    } catch {
      return false;
    }
  }
}
