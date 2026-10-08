# ADR-0008 — Sign in with mobile number + password, no OTP

**Status:** Accepted (2026-10-08, owner's decision). Replaces the OTP flow of
`docs/phases/phase-02-authentication.md`.

## Context
Phase 2 specifies mobile number + SMS OTP. The owner does not want OTP: users give a name, a
mobile number and a password; the password has no strength rules ("even one character must
work"); the app must not ask for sign-in again once signed in.

## Decision
- **Create account:** name, mobile number, password — all mandatory. **Sign in:** number + password.
- **No password policy:** 1 to 200 characters, anything goes.
- Passwords are stored only as salted **scrypt** hashes (`scrypt$N$r$p$salt$hash`, Node's
  standard library; cost parameters travel with the hash so they can be raised later).
- **Staying signed in:** 15-minute access token + refresh token that is rotated on every use and
  whose lifetime slides 180 days from last use, on phone and TV alike. An app that is opened at
  least once in 180 days never asks again. The session is saved on the device encrypted
  (Tink AEAD, key in the Android Keystore).
- Everything else from Phase 2 is kept: one session per device, refresh reuse detection,
  logout / logout-all / session list, global auth guard, hashed IPs, masked phone numbers in logs.
- Not built: OTP requests, SMS providers, test numbers, the TV numeric keypad (TV uses the
  system keyboard, since a password is not digits-only).

## Consequences — read these
- **The mobile number is not verified.** Anyone can create an account with any number,
  including someone else's, and the real owner then cannot register it. The number is only a
  username. Fixing this later means adding OTP (or another proof) after all.
- **Weak passwords are allowed, so accounts are guessable.** The only brake is rate limiting:
  10 wrong passwords for a number lock that number for the rest of the hour (also for the right
  password), plus 60 sign-in attempts per IP per hour. A one-character password still falls to
  a patient attacker within days. Before real users store anything private, add a minimum
  length or a second factor.
- **No "forgot password".** There is no verified channel to reset through. A user who forgets
  the password is locked out until an admin tool exists (Phase 13).
- That lock is also a nuisance vector: anyone can lock a number's sign-in for an hour by
  guessing wrong ten times. Existing sessions are unaffected.
- Register reveals whether a number has an account (it must, to refuse duplicates). Sign-in
  does not: wrong password and unknown number give the same answer in the same time.
- Phase 18's "Play review account" exception is no longer needed: reviewers can simply be
  given a number and password.
