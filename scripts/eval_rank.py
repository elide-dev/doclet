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

def rank_contract(question, index):
    """BM25 normalizes for detail length; keep split words from camel-case API names."""
    def tokens(text):
        split = re.sub(r"([a-z])([A-Z])", r"\1 \2", text)
        return words(split)

    docs = {key: Counter(tokens(value)) for key, value in index.items()}
    frequencies = Counter(term for doc in docs.values() for term in doc)
    lengths = {key: sum(doc.values()) for key, doc in docs.items()}
    mean_length = sum(lengths.values()) / len(docs) or 1
    query = sorted(set(tokens(question)))

    def score(key):
        norm = 1.2 * (0.25 + 0.75 * lengths[key] / mean_length)
        total = 0
        for term in query:
            freq = docs[key][term]
            if freq:
                df = frequencies[term]
                total += math.log1p((len(docs) - df + 0.5) / (df + 0.5)) * freq * 2.2 / (freq + norm)
        return total

    return sorted(docs, key=lambda key: (-score(key), key))
