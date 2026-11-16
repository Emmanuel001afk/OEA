# Console signature scan

Console → Security → Malware signature scan provides an on-demand, local exact-file
SHA-256 matcher. It scans readable base and split APK files of installed non-system
apps, or one file selected through Android's document picker. No files or hashes are
uploaded. Inaccessible files are counted, not reported as clean. The EICAR standard
antivirus test file is recognized as a harmless test, not malware.

No malware signatures are bundled. Import a UTF-8 plain-text list from a trusted
provider: one 64-character SHA-256 hash per line, optionally followed by whitespace
and a label; blank lines and lines beginning with `#` are ignored. Import replaces the
previous list atomically, with a 20 MB / 200,000 unique signature limit. Invalid lines
are counted; an empty or invalid list does not replace the previous one. The user must
verify the provider, download and update the list independently; OEA does not verify
its authenticity, freshness or licensing. It does not download signatures or collect
API credentials. To obtain an example list:

1. Visit [MalwareBazaar exports](https://bazaar.abuse.ch/export/) and read the
   provider's terms and format documentation. Its exports currently require an
   Auth-Key from the abuse.ch authentication portal linked there.
2. Download a **plain-text SHA-256 hash list**, not malware samples or a CSV/ZIP,
   to your phone. Ensure it fits the limits above. OEA accepts hashes and optional
   labels, not provider CSV records. Recent-only lists cover only recent samples;
   full lists may exceed OEA's limits and need a suitably sized subset.
3. In Console → Security → Malware signature scan choose **Import SHA-256 signature
   list**, then select that text file. Replace it periodically with a fresh export.
   Do not paste your Auth-Key into OEA or share it in chat.
4. Enable **Check newly installed and updated apps** if desired, and allow OEA's
   security notifications. Install/update events are queued for a background APK
   check when Android permits; this happens after installation and may be delayed.
   If notifications are blocked, warnings cannot be shown. Scan existing apps
   separately, and re-scan after updating the signature list.

A match means only that the exact file hash appears in the imported list; verify the
source before taking action. APK variants, archive members and unknown malware will
not match, and Android restricts access to other apps' private files. There is no
pre-install or continuous scanning, automatic feed, behavioral analysis, web protection, quarantine,
or guarantee equivalent to a commercial antivirus. Android and Play Protect remain
important independent layers of protection. Do not treat an empty match list as a
safety guarantee.
