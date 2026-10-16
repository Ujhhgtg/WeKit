"""Read real DEX class definitions without decoding modified-UTF8 instruction listings."""
from collections import Counter
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def dex_definitions(archive, tools):
    definitions = Counter()
    descriptor = re.compile(rb"^\s*Class descriptor\s*:\s*'([^']+)'[\r\n]*$")
    with tempfile.TemporaryDirectory(prefix="wekit-dex-check-") as directory:
        for entry in archive.namelist():
            if not re.fullmatch(r"classes(?:\d+)?\.dex", entry):
                continue
            path = Path(directory) / entry
            with archive.open(entry) as source, path.open("wb") as output:
                shutil.copyfileobj(source, output)
            with tempfile.TemporaryFile() as errors:
                process = subprocess.Popen([str(tools / "dexdump"), str(path)],
                                           stdout=subprocess.PIPE, stderr=errors)
                try:
                    for line in process.stdout:
                        if not line.lstrip().startswith(b"Class descriptor"):
                            continue
                        match = descriptor.fullmatch(line)
                        if match is None:
                            raise ValueError(f"invalid class descriptor output in {entry}")
                        definitions[match[1].decode("utf-8")] += 1
                    status = process.wait()
                    if status:
                        errors.seek(0)
                        raise subprocess.CalledProcessError(status, process.args,
                                                            stderr=errors.read(8192))
                finally:
                    process.stdout.close()
                    if process.poll() is None:
                        process.kill()
                        process.wait()
    assert definitions, "APK has no DEX class definitions"
    assert all(count == 1 for count in definitions.values()), "duplicate class definition across DEX"
    return definitions
