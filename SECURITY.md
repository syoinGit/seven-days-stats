# Security boundary

WATCHPOINT is a private, small-group dashboard. It is designed to minimize accidental exposure,
not to promise an impossible “unhackable” system.

## Application rules

- `/` is a public landing page; dashboard, detail, diary, and server data pages require an
  authenticated session.
- `VIEWER` is read-only. Its HTML responses are marked `no-store`, player names are replaced by
  stable `SURVIVOR-xx` aliases, player dossier links are removed, and `Steam_*`/`EOS_*` values are
  masked as `EXTERNAL-ID`.
- `PLAYER` can create/delete their own posts, like posts, and change their own status while online.
  `ADMIN` additionally owns maintenance pages.
- Player detail routes redirect a guest before loading the detail view. Authorization is enforced
  server-side; hiding a button is only a usability feature.
- CSRF protection, BCrypt password hashes, HttpOnly/SameSite session cookies, session-id rotation,
  CSP, HSTS (when HTTPS is used), frame denial, referrer restrictions, and restrictive browser
  capability headers are enabled.
- Error pages intentionally omit exception messages, stack traces, binding errors, and request
  details.

## Deployment checklist

1. Set long random `POSTGRES_PASSWORD`, `WATCHPOINT_BOOTSTRAP_PASSWORD`, and
   `SEVEN_DAYS_TELNET_PASSWORD` values in the host-only `.env` file.
2. Run with the production Spring profile, which enables secure cookies, and terminate HTTPS at the reverse proxy.
3. Bind PostgreSQL and Telnet to private interfaces; do not expose ports 5432/8081 to the public
   internet.
4. Remove legacy Nginx Basic Auth only after application login, guest login, HTTPS, and logout have
   been verified.
5. Keep `.env`, 7DTD save/log directories, database backups, and reverse-proxy logs out of Git and
   public object storage.
6. Review guest pages after every template or query change. The response sanitizer is a final
   safety net, not a substitute for typed guest DTOs and least-privilege database access.

## Limits

An administrator with database/filesystem access can still read the original data. A compromised
EC2 host, reverse proxy, browser, or database is outside the application’s protection boundary.
