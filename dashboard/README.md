# dashboard

The control-room dashboard (plan doc screens W1–W3): operator sign-in, a live list and map of SOS, and the detail view where an operator replies to the sender and sets a status. React + Vite in JavaScript (decision D3), Leaflet with OpenStreetMap tiles, plain CSS with the category colours from the app. Charts for the experiments (W4) come in week 9.

## Run it locally

Start the API first (`cd backend`, then `npm start`; on Windows PowerShell use `npm.cmd start`). Then:

```bash
cd dashboard
npm install
npm run dev
```

Open http://localhost:5173 and sign in with the operator from `backend/.env`. Vite passes `/api` to the API on port 8080.

## Production

`npm run build` writes `dist/`, and the API server hosts it at `/` on the same origin (see `render.yaml` and `backend/README.md`). After a build, `npm start` in `backend` also serves it locally at http://localhost:8080.

## How it stays live

The page listens to `GET /api/v1/stream` (Server-Sent Events). Each `sos.new`, `sos.updated` or `ack.new` reloads the list and the open SOS. A new SOS plays two short tones (the bell button turns this off) and puts the count of new SOS in the tab title.
