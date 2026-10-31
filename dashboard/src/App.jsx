import { useEffect, useState } from 'react';
import { api } from './api.js';
import LiveOps from './LiveOps.jsx';
import Login from './Login.jsx';
import Results from './Results.jsx';

/** W1 until an operator is signed in, then W2/W3, or W4 at #results. */
export default function App() {
  const [operator, setOperator] = useState(undefined); // undefined: still checking the session
  const [page, setPage] = useState(() => location.hash);

  useEffect(() => {
    api.me().then(setOperator, () => setOperator(null));
  }, []);

  useEffect(() => {
    const onHash = () => setPage(location.hash);
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  if (operator === undefined) return <div className="splash">Loading…</div>;
  if (!operator) return <Login onSignedIn={setOperator} />;
  if (page === '#results') return <Results />;
  return (
    <LiveOps
      operator={operator}
      onSignedOut={() => setOperator(null)}
      onLogout={() => api.logout().finally(() => setOperator(null))}
    />
  );
}
