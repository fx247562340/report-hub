import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { getUser } from './api/client'
import Shell from './layout/Shell'
import Login from './pages/Login'
import Home from './pages/Home'
import Reports from './pages/Reports'
import ReportRun from './pages/ReportRun'
import Datasources from './pages/Datasources'
import Endpoints from './pages/Endpoints'
import Datasets from './pages/Datasets'
import Relations from './pages/Relations'
import Users from './pages/Users'
import Profile from './pages/Profile'
import About from './pages/About'
import Sync from './pages/Sync'

function RequireAuth({ children }: { children: JSX.Element }) {
  const user = getUser()
  const loc = useLocation()
  if (!user) return <Navigate to="/login" replace state={{ from: loc }} />
  return children
}

function RequireAdmin({ children }: { children: JSX.Element }) {
  const user = getUser()
  if (!user) return <Navigate to="/login" replace />
  if (user.role !== 'admin') return <Navigate to="/" replace />
  return children
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <Shell />
          </RequireAuth>
        }
      >
        <Route index element={<Home />} />
        <Route path="reports" element={<Reports />} />
        <Route path="reports/:code" element={<ReportRun />} />
        <Route path="sync" element={<RequireAdmin><Sync /></RequireAdmin>} />
        <Route path="datasources" element={<RequireAdmin><Datasources /></RequireAdmin>} />
        <Route path="endpoints" element={<RequireAdmin><Endpoints /></RequireAdmin>} />
        <Route path="datasets" element={<RequireAdmin><Datasets /></RequireAdmin>} />
        <Route path="relations" element={<RequireAdmin><Relations /></RequireAdmin>} />
        <Route path="users" element={<RequireAdmin><Users /></RequireAdmin>} />
        <Route path="profile" element={<Profile />} />
        <Route path="about" element={<About />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
