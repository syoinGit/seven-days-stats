#!/usr/bin/env python3
"""Build the transfer archive and EC2 paste script from the reviewed source files."""

from pathlib import Path
import tarfile


def main():
    root = Path(__file__).resolve().parents[2]
    output = root / "target"
    output.mkdir(exist_ok=True)
    files = (
        "infra/docker/rubinco-game-control",
        "infra/docker/rubinco-game-control.sudoers",
        "infra/systemd/rubinco-watchpoint-restricted.conf.example",
    )
    archive = output / "rubinco-permissions.tar.gz"
    with tarfile.open(archive, "w:gz") as bundle:
        for name in files:
            bundle.add(root / name, arcname=name)

    lines = [
        "#!/bin/bash",
        "# Paste this entire file into the EC2 terminal as ec2-user.",
        "# Creates staging files only; does not install sudoers or restart services.",
        "(",
        "set -eu",
        "umask 077",
        "RUBINCO_STAGE='/home/ec2-user/rubinco/apps/watchpoint/permissions'",
        'mkdir -p "$RUBINCO_STAGE/infra/docker" "$RUBINCO_STAGE/infra/systemd"',
    ]
    for index, name in enumerate(files):
        delimiter = f"RUBINCO_FILE_{index}_EOF"
        content = (root / name).read_text()
        if delimiter in content.splitlines():
            raise ValueError("Source contains a heredoc delimiter")
        lines.extend([
            f'cat > "$RUBINCO_STAGE/{name}" <<\'{delimiter}\'',
            content.rstrip("\n"),
            delimiter,
        ])
    lines.extend([
        'bash -n "$RUBINCO_STAGE/infra/docker/rubinco-game-control"',
        'printf "%s\\n" "Staging files created. Continue with step 1 (backup), then step 2 (helper installation)."',
        'ls -l "$RUBINCO_STAGE/infra/docker/" "$RUBINCO_STAGE/infra/systemd/"',
        ")",
        "",
    ])
    paste = output / "rubinco-permissions-paste.sh"
    paste.write_text("\n".join(lines))
    print(f"Created {archive}")
    print(f"Created {paste}")


if __name__ == "__main__":
    main()
