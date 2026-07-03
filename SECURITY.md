# Security Policy

This project handles Medical and dental practice activities workflows, including patient/resident
health data. Treat vulnerabilities as potentially high impact even when the
demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real patient/resident/client data exposure
- authorization bypass
- Clinical Practice Governor bypass
- audit-ledger tampering
- over-disclosure in reports or exports

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on patient/resident data, policy enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real patient/resident/client data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
