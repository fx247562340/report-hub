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

function RequireAuth({ children }: { children: JSX.Element }) {
  const user = getUser()
  const loc = useLocation()
  if (!user) return <Navigate to="/login" replace state={{ from: loc }} />
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
        <Route path="datasources" element={<Datasources />} />
        <Route path="endpoints" element={<Endpoints />} />
        <Route path="datasets" element={<Datasets />} />
        <Route path="relations" element={<Relations />} />
        <Route path="users" element={<Users />} />
        <Route path="profile" element={<Profile />} />
        <Route path="about" element={<About />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
