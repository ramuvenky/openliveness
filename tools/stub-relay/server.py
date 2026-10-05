"""
Throwaway stub relay for mobile SDK development.

Accepts any attestation payload without verification and returns a stub
attestation_token. Mirrors the HTTP endpoints and the WebSocket
session_acknowledged frame that the real relay (owned by Person 1) will
expose, so the Week 2 phone→relay happy path is testable locally.

Usage:
    python -m venv .venv && .venv/bin/pip install -r requirements.txt
    .venv/bin/python server.py
    # HTTP: http://127.0.0.1:8787
    # WS:   ws://127.0.0.1:8787/cdl/session/<id>/ws
"""
from __future__ import annotations

import secrets
from flask import Flask, jsonify, request
from flask_sock import Sock

app = Flask(__name__)
sock = Sock(app)


@app.post("/cdl/device/register")
def device_register():
    _ = request.get_json(silent=True) or {}
    return jsonify({"challenge": secrets.token_urlsafe(32)})


@app.post("/cdl/device/attest")
def device_attest():
    _ = request.get_json(silent=True) or {}
    return jsonify({"device_token": f"stub-device-{secrets.token_hex(8)}"})


@app.post("/cdl/session/<session_id>/integrity-nonce")
def integrity_nonce(session_id: str):
    return jsonify({"nonce": secrets.token_urlsafe(32)})


@app.post("/cdl/session/<session_id>/complete")
def complete(session_id: str):
    payload = request.get_json(silent=True) or {}
    print(f"[relay] session={session_id} assurance={payload.get('assurance_level')} "
          f"scores={payload.get('layer_scores')}")
    return jsonify({"accepted": True, "attestation_token": f"stub-attest-{secrets.token_hex(16)}"})


@sock.route("/cdl/session/<session_id>/ws")
def session_ws(ws, session_id: str):
    ws.send('{"type":"session_acknowledged","session_id":"' + session_id + '"}')
    while True:
        msg = ws.receive(timeout=30)
        if msg is None:
            break


if __name__ == "__main__":
    app.run(host="127.0.0.1", port=8787, debug=False)
