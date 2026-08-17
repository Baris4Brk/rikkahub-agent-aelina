#!/usr/bin/env python3
"""Rebuild the immutable LearningDatabase v2 Room schema from the v3 export.

Learning DB v3 was a pure additive migration over v2: it created the four Policy
tables below and did not alter any pre-existing table.  The original v2 export
was omitted when that migration landed.  This script recovers it mechanically
from the checked-in v3 Room export and computes the identity with Room 2.8's
SchemaIdentityKey algorithm.  The algorithm is checked against every other
checked-in LearningDatabase schema before v2 is written, so the identity is not
an invented/stamped value.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_DIR = (
    ROOT
    / "app"
    / "schemas"
    / "me.rerere.rikkahub.learning.storage.LearningDatabase"
)
V3_ONLY_TABLES = {
    "learning_policies",
    "policy_evidence",
    "policy_revisions",
    "policy_lineage",
}
ROOM_ID_SEPARATOR = "?:?"


def md5(value: str) -> str:
    return hashlib.md5(value.encode("utf-8")).hexdigest()


def english_sorted(values: list[str]) -> list[str]:
    # SchemaIdentityKey uses Locale.ENGLISH lowercase ordering.  Room identifiers
    # in this schema are ASCII, for which casefold/lower have the same ordering.
    return sorted(values, key=str.lower)


def field_identity(field: dict) -> str:
    identity = (
        f"{field['columnName']}-{field.get('affinity') or 'TEXT'}-"
        f"{str(bool(field.get('notNull', False))).lower()}"
    )
    if field.get("defaultValue") is not None:
        identity += f"-defaultValue={field['defaultValue']}"
    return identity


def primary_key_identity(primary_key: dict) -> str:
    columns = ", ".join(primary_key.get("columnNames", []))
    auto_generate = str(bool(primary_key.get("autoGenerate", False))).lower()
    return f"{auto_generate}-[{columns}]"


def index_identity(index: dict) -> str:
    identity = (
        f"{str(bool(index.get('unique', False))).lower()}-{index['name']}-"
        f"{','.join(index.get('columnNames', []))}"
    )
    if index.get("orders"):
        identity += f"-{','.join(index['orders'])}"
    return identity


def foreign_key_identity(foreign_key: dict) -> str:
    return "-".join(
        (
            foreign_key["table"],
            ",".join(foreign_key.get("referencedColumns", [])),
            ",".join(foreign_key.get("columns", [])),
            foreign_key["onDelete"],
            foreign_key["onUpdate"],
            str(bool(foreign_key.get("deferred", False))).lower(),
        )
    )


def entity_identity(entity: dict) -> str:
    parts = [entity["tableName"], primary_key_identity(entity["primaryKey"])]
    parts += english_sorted([field_identity(value) for value in entity.get("fields", [])])
    parts += english_sorted([index_identity(value) for value in entity.get("indices", [])])
    parts += english_sorted(
        [foreign_key_identity(value) for value in entity.get("foreignKeys", [])]
    )
    return md5("".join(f"{part}{ROOM_ID_SEPARATOR}" for part in parts))


def view_identity(view: dict) -> str:
    # DatabaseView.idKey is its canonical CREATE VIEW query.
    return view["createSql"]


def database_identity(database: dict) -> str:
    identities = [entity_identity(entity) for entity in database["entities"]]
    identities += [view_identity(view) for view in database.get("views", [])]
    return md5(
        "".join(
            f"{identity}{ROOM_ID_SEPARATOR}"
            for identity in english_sorted(identities)
        )
    )


def read_schema(version: int) -> dict:
    return json.loads((SCHEMA_DIR / f"{version}.json").read_text(encoding="utf-8"))


def verify_room_algorithm() -> None:
    verified = 0
    for path in sorted(SCHEMA_DIR.glob("*.json")):
        if path.stem == "2":
            continue
        schema = json.loads(path.read_text(encoding="utf-8"))
        database = schema["database"]
        computed = database_identity(database)
        if computed != database["identityHash"]:
            raise RuntimeError(
                f"Room identity algorithm mismatch for {path.name}: "
                f"expected {database['identityHash']}, computed {computed}"
            )
        verified += 1
    if verified < 2:
        raise RuntimeError("Too few independent Room exports to verify the identity algorithm")


def main() -> None:
    verify_room_algorithm()
    schema = read_schema(3)
    database = schema["database"]
    if database["version"] != 3:
        raise RuntimeError("LearningDatabase/3.json does not describe version 3")

    present = {entity["tableName"] for entity in database["entities"]}
    if not V3_ONLY_TABLES.issubset(present):
        raise RuntimeError("The frozen v3 Policy table set is incomplete")

    database["entities"] = [
        entity
        for entity in database["entities"]
        if entity["tableName"] not in V3_ONLY_TABLES
    ]
    if {entity["tableName"] for entity in database["entities"]} != present - V3_ONLY_TABLES:
        raise RuntimeError("v2 recovery changed an entity outside the v3 additive table set")
    database["version"] = 2
    database["identityHash"] = database_identity(database)
    room_identity_queries = [
        index
        for index, query in enumerate(database.get("setupQueries", []))
        if "INSERT OR REPLACE INTO room_master_table" in query
    ]
    if len(room_identity_queries) != 1:
        raise RuntimeError("v3 export does not contain one canonical Room identity query")
    query_index = room_identity_queries[0]
    source_identity = read_schema(3)["database"]["identityHash"]
    source_query = database["setupQueries"][query_index]
    if source_query.count(source_identity) != 1:
        raise RuntimeError("v3 Room setup query is not bound to its exported identity")
    database["setupQueries"][query_index] = source_query.replace(
        source_identity,
        database["identityHash"],
    )

    destination = SCHEMA_DIR / "2.json"
    destination.write_text(
        json.dumps(schema, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"wrote {destination} ({database['identityHash']})")


if __name__ == "__main__":
    main()
