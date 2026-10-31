import { useEffect, useState } from 'react';
import { api } from './api.js';
import LiveOps from './LiveOps.jsx';
import Login from './Login.jsx';

/** W1 until an operator is signed in, then W2/W3. */
export default function App() {
  const [operator, setOperator] = useState(undefined); // undefined: still checking the session

  useEffect(() => {
    api.me().then(setOperator, () => setOperator(null));
  }, []);

  if (operator === undefined) return <div className="splash">Loading…</div>;
  if (!operator) return <Login onSignedIn={setOperator} />;
  return (
    <LiveOps
      operator={operator}
      onSignedOut={() => setOperator(null)}
      onLogout={() => api.logout().finally(() => setOperator(null))}
    />
  );
}
