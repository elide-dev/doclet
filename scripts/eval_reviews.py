"""Checksum-bound semantic corrections shared by the local benchmark reports."""
import json
from pathlib import Path
import re


def load_reviews(path):
    if path is None:
        return {}
    document = json.loads(path.read_bytes())
    if not isinstance(document, dict):
        raise ValueError("Reviews must map report SHA-256 to correction lists")
    for digest, corrections in document.items():
        if not re.fullmatch(r"[0-9a-f]{64}", digest) or not isinstance(corrections, list) or not corrections:
            raise ValueError("Each review needs a report SHA-256 and a nonempty correction list")
        for review in corrections:
            required = {"route", "question", "correct", "reason"}
            if (not isinstance(review, dict) or not required.issubset(review)
                    or set(review) - required - {"report"} or type(review["correct"]) is not bool
                    or not all(isinstance(review[key], str) and review[key].strip()
                               for key in ("route", "question", "reason"))):
                raise ValueError(f"Invalid correction in review {digest}")
            if "report" in review and (not isinstance(review["report"], str)
                    or not review["report"].strip() or Path(review["report"]).name != review["report"]):
                raise ValueError(f"Invalid report basename in review {digest}")
    return document


def review_corrections(path, digest, reviews, rows):
    corrections = {}
    for expected, items in reviews.items():
        if expected != digest and any(review.get("report") == path.name for review in items):
            raise ValueError(f"{path.name}: report checksum differs from its review")
    for review in reviews.get(digest, []):
        if review.get("report", path.name) != path.name:
            raise ValueError(f"{path.name}: review report basename does not match")
        route, question = review["route"], review["question"]
        key = (route, question)
        row = rows.get(route, {}).get(question)
        if not row or key in corrections or row["correct"] == review["correct"]:
            raise ValueError(f"{path.name}: unknown, duplicate, or no-op correction for {route}: {question}")
        corrections[key] = review
    return corrections
