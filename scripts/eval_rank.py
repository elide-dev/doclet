"""Shared lexical ranking for document discovery."""
from collections import Counter
import math
import re


def words(text):
    return re.findall(r"[a-z0-9]+", text.lower())


def rank(question, index):
    query = set(words(question))
    docs = {name: Counter(words(entry)) for name, entry in index.items()}
    freq = Counter(term for doc in docs.values() for term in doc)
    def score(name):
        return sum(math.log1p(len(docs) / freq[t]) * (1 + math.log(docs[name][t]))
                   for t in sorted(query.intersection(docs[name])))
    return sorted(docs, key=lambda name: (-score(name), name))
