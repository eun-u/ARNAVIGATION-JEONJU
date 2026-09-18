from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from threading import RLock
from typing import Any


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class Database:
    SCHEMA_VERSION = 2

    def __init__(self, path: Path) -> None:
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = RLock()
        self.connection = sqlite3.connect(path, check_same_thread=False)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute("PRAGMA foreign_keys=ON")
        self.connection.execute("PRAGMA journal_mode=WAL")
        self._migrate()

    def _migrate(self) -> None:
        with self._lock, self.connection:
            self.connection.executescript("""
                CREATE TABLE IF NOT EXISTS app_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS poc_session_state (
                    session_id TEXT PRIMARY KEY, route_revision INTEGER NOT NULL, avoidances_json TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS poc_events (
                    session_id TEXT NOT NULL, event_id TEXT NOT NULL, request_json TEXT NOT NULL,
                    response_json TEXT NOT NULL, created_at TEXT NOT NULL,
                    PRIMARY KEY(session_id,event_id)
                );
                CREATE TABLE IF NOT EXISTS edge_state (
                    edge_id TEXT PRIMARY KEY, state_json TEXT NOT NULL, revision INTEGER NOT NULL, updated_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS edge_status_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, edge_id TEXT NOT NULL,
                    before_json TEXT NOT NULL, after_json TEXT NOT NULL,
                    actor TEXT, source TEXT NOT NULL, observation_id TEXT, changed_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS observation_candidates (
                    candidate_id TEXT PRIMARY KEY, edge_id TEXT NOT NULL, payload_json TEXT NOT NULL,
                    status TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS observation_reviews (
                    review_id INTEGER PRIMARY KEY AUTOINCREMENT, candidate_id TEXT NOT NULL,
                    decision TEXT NOT NULL, result TEXT, reviewer TEXT NOT NULL, observed_at TEXT NOT NULL,
                    reason TEXT NOT NULL, measurements_json TEXT NOT NULL, created_at TEXT NOT NULL,
                    FOREIGN KEY(candidate_id) REFERENCES observation_candidates(candidate_id)
                );
                CREATE TABLE IF NOT EXISTS route_sessions (
                    session_id TEXT PRIMARY KEY, request_json TEXT NOT NULL, route_json TEXT,
                    comparison_json TEXT, graph_revision INTEGER NOT NULL,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL, expires_at TEXT NOT NULL,
                    temporary_blocked_edges_json TEXT NOT NULL DEFAULT '[]'
                );
            """)
            session_columns = {
                row["name"]
                for row in self.connection.execute("PRAGMA table_info(route_sessions)").fetchall()
            }
            if "temporary_blocked_edges_json" not in session_columns:
                self.connection.execute(
                    "ALTER TABLE route_sessions ADD COLUMN temporary_blocked_edges_json TEXT NOT NULL DEFAULT '[]'"
                )
            self.connection.execute("INSERT OR IGNORE INTO app_meta(key,value) VALUES('schema_version',?)", (str(self.SCHEMA_VERSION),))
            self.connection.execute("UPDATE app_meta SET value=? WHERE key='schema_version'", (str(self.SCHEMA_VERSION),))
            self.connection.execute("INSERT OR IGNORE INTO app_meta(key,value) VALUES('graph_revision','0')")

    @property
    def graph_revision(self) -> int:
        row = self.connection.execute("SELECT value FROM app_meta WHERE key='graph_revision'").fetchone()
        return int(row[0])

    def edge_overlays(self) -> list[dict[str, Any]]:
        rows = self.connection.execute("SELECT edge_id,state_json FROM edge_state").fetchall()
        return [{"edge_id": row["edge_id"], "values": json.loads(row["state_json"])} for row in rows]

    def record_edge_change(self, edge_id: str, before: dict[str, Any], after: dict[str, Any], actor: str | None, source: str, observation_id: str | None = None) -> int:
        now = utc_now().isoformat()
        persisted = {key: after.get(key) for key in ("blocked", "block_reason", "accessibility_status", "status_source", "verified", "updated_at", "stairs", "slope", "width", "curb_height", "wheelchair_accessible", "accessibility_source") if key in after}
        with self._lock, self.connection:
            revision = self.graph_revision + 1
            self.connection.execute("UPDATE app_meta SET value=? WHERE key='graph_revision'", (str(revision),))
            self.connection.execute("INSERT INTO edge_state(edge_id,state_json,revision,updated_at) VALUES(?,?,?,?) ON CONFLICT(edge_id) DO UPDATE SET state_json=excluded.state_json, revision=excluded.revision, updated_at=excluded.updated_at", (edge_id, json.dumps(persisted, ensure_ascii=False), revision, now))
            self.connection.execute("INSERT INTO edge_status_history(edge_id,before_json,after_json,actor,source,observation_id,changed_at) VALUES(?,?,?,?,?,?,?)", (edge_id, json.dumps(before, ensure_ascii=False), json.dumps(after, ensure_ascii=False), actor, source, observation_id, now))
        return revision

    def edge_history(self, edge_id: str) -> list[dict[str, Any]]:
        rows = self.connection.execute("SELECT * FROM edge_status_history WHERE edge_id=? ORDER BY id DESC", (edge_id,)).fetchall()
        return [{**dict(row), "before": json.loads(row["before_json"]), "after": json.loads(row["after_json"])} for row in rows]

    def seed_candidates(self, candidates: list[dict[str, Any]]) -> None:
        now = utc_now().isoformat()
        with self._lock, self.connection:
            for item in candidates:
                created = item.get("created_at") or now
                self.connection.execute("INSERT OR IGNORE INTO observation_candidates(candidate_id,edge_id,payload_json,status,created_at,updated_at) VALUES(?,?,?,?,?,?)", (item["candidate_id"], item["edge_id"], json.dumps(item, ensure_ascii=False), item.get("status", "pending"), created, now))

    def create_candidate(self, candidate: dict[str, Any]) -> dict[str, Any]:
        now = utc_now().isoformat()
        payload = {**candidate, "status": "pending", "verified": False, "created_at": now, "updated_at": now}
        with self._lock, self.connection:
            self.connection.execute(
                "INSERT INTO observation_candidates(candidate_id,edge_id,payload_json,status,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                (payload["candidate_id"], payload["edge_id"], json.dumps(payload, ensure_ascii=False), "pending", now, now),
            )
        return self.get_candidate(payload["candidate_id"]) or payload

    def list_candidates(self, status: str | None = None) -> list[dict[str, Any]]:
        sql, params = "SELECT * FROM observation_candidates", ()
        if status:
            sql, params = sql + " WHERE status=?", (status,)
        rows = self.connection.execute(sql + " ORDER BY candidate_id", params).fetchall()
        return [self._candidate(row) for row in rows]

    def get_candidate(self, candidate_id: str) -> dict[str, Any] | None:
        row = self.connection.execute("SELECT * FROM observation_candidates WHERE candidate_id=?", (candidate_id,)).fetchone()
        return self._candidate(row) if row else None

    def _candidate(self, row: sqlite3.Row) -> dict[str, Any]:
        payload = json.loads(row["payload_json"])
        payload.update(
            status=row["status"],
            verified=row["status"] == "approved",
            updated_at=row["updated_at"],
        )
        review = self.connection.execute("SELECT * FROM observation_reviews WHERE candidate_id=? ORDER BY review_id DESC LIMIT 1", (row["candidate_id"],)).fetchone()
        if review:
            latest = dict(review)
            latest["measurements"] = json.loads(latest.pop("measurements_json"))
            payload["latest_review"] = latest
        return payload

    def review_candidate(self, candidate_id: str, review: dict[str, Any]) -> dict[str, Any]:
        now = utc_now().isoformat()
        with self._lock, self.connection:
            self.connection.execute("INSERT INTO observation_reviews(candidate_id,decision,result,reviewer,observed_at,reason,measurements_json,created_at) VALUES(?,?,?,?,?,?,?,?)", (candidate_id, review["decision"], review.get("result"), review["reviewer"], review["observed_at"], review["reason"], json.dumps(review.get("measurements", {}), ensure_ascii=False), now))
            self.connection.execute("UPDATE observation_candidates SET status=?, updated_at=? WHERE candidate_id=?", (review["decision"], now, candidate_id))
        return self.get_candidate(candidate_id) or {}

    def save_session(
        self,
        session_id: str,
        request: dict[str, Any],
        route: dict[str, Any] | None,
        comparison: dict[str, Any] | None,
        expires_at: datetime,
        temporary_blocked_edge_ids: list[str] | None = None,
    ) -> None:
        now = utc_now().isoformat()
        temporary_blocks = temporary_blocked_edge_ids or []
        with self._lock, self.connection:
            self.connection.execute(
                "INSERT INTO route_sessions(session_id,request_json,route_json,comparison_json,graph_revision,created_at,updated_at,expires_at,temporary_blocked_edges_json) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET request_json=excluded.request_json,route_json=excluded.route_json,comparison_json=excluded.comparison_json,graph_revision=excluded.graph_revision,updated_at=excluded.updated_at,expires_at=excluded.expires_at,temporary_blocked_edges_json=excluded.temporary_blocked_edges_json",
                (
                    session_id,
                    json.dumps(request),
                    json.dumps(route) if route else None,
                    json.dumps(comparison) if comparison else None,
                    self.graph_revision,
                    now,
                    now,
                    expires_at.isoformat(),
                    json.dumps(temporary_blocks),
                ),
            )

    def get_session(self, session_id: str, include_expired: bool = False) -> dict[str, Any] | None:
        row = self.connection.execute("SELECT * FROM route_sessions WHERE session_id=?", (session_id,)).fetchone()
        if not row or (not include_expired and datetime.fromisoformat(row["expires_at"]) <= utc_now()):
            return None
        return self._session(row)

    def active_sessions(self) -> list[dict[str, Any]]:
        rows = self.connection.execute("SELECT * FROM route_sessions WHERE expires_at>?", (utc_now().isoformat(),)).fetchall()
        return [self._session(row) for row in rows]

    @staticmethod
    def _session(row: sqlite3.Row) -> dict[str, Any]:
        result = dict(row)
        for key in ("request_json", "route_json", "comparison_json"):
            result[key.removesuffix("_json")] = json.loads(result.pop(key)) if result[key] else None
        result["temporary_blocked_edge_ids"] = json.loads(
            result.pop("temporary_blocked_edges_json", "[]") or "[]"
        )
        return result

    def close(self) -> None:
        self.connection.close()

    def poc_state(self, session_id: str) -> dict:
        row = self.connection.execute("SELECT * FROM poc_session_state WHERE session_id=?",(session_id,)).fetchone()
        return {"route_revision":row["route_revision"], "avoidances":json.loads(row["avoidances_json"])} if row else {"route_revision":1,"avoidances":{}}

    def poc_event(self, session_id: str, event_id: str) -> dict | None:
        row = self.connection.execute("SELECT * FROM poc_events WHERE session_id=? AND event_id=?",(session_id,event_id)).fetchone()
        return dict(row) if row else None

    def commit_poc_event(self, session_id, event_id, request_json, response, route_request, route, state, expires_at):
        """Route, avoidance state and idempotency response are one SQLite transaction."""
        now=utc_now().isoformat()
        blocked=[e for e,a in state["avoidances"].items() if a["status"] != "removed"]
        with self._lock, self.connection:
            self.connection.execute("UPDATE route_sessions SET request_json=?, route_json=?, comparison_json=NULL, graph_revision=?, updated_at=?, expires_at=?, temporary_blocked_edges_json=? WHERE session_id=?",
                (json.dumps(route_request),json.dumps(route) if route else None,self.graph_revision,now,expires_at.isoformat(),json.dumps(blocked),session_id))
            self.connection.execute("INSERT INTO poc_session_state VALUES(?,?,?) ON CONFLICT(session_id) DO UPDATE SET route_revision=excluded.route_revision,avoidances_json=excluded.avoidances_json",
                (session_id,state["route_revision"],json.dumps(state["avoidances"])))
            self.connection.execute("INSERT INTO poc_events VALUES(?,?,?,?,?)",(session_id,event_id,request_json,json.dumps(response),now))
