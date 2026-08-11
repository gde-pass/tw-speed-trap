"""Bytes → text with encoding detection.

Taiwanese open data is frequently Big5/CP950 rather than UTF-8. We try strict
UTF-8 first (misdecoding UTF-8 as Big5 produces silent mojibake, the reverse
raises), then CP950 (a superset of Big5), then fall back to charset-normalizer.
"""

from charset_normalizer import from_bytes


class DecodeError(ValueError):
    pass


def decode_bytes(data: bytes) -> str:
    if data.startswith(b"\xef\xbb\xbf"):
        return data.decode("utf-8-sig")
    for encoding in ("utf-8", "cp950", "big5"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    best = from_bytes(data).best()
    if best is None:
        raise DecodeError("could not detect text encoding")
    return str(best)
