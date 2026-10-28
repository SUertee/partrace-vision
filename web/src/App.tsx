import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import AssistantPage from "./assistant/AssistantPage";
import { TraceSessionProvider } from "./assistant/session";
import EventDetailPage from "./pages/EventDetailPage";
import EventsPage from "./pages/EventsPage";
import HomePage from "./pages/HomePage";
import MessagesPage from "./pages/MessagesPage";
import ProfilePage from "./pages/ProfilePage";
import { ReviewDetailPage, ReviewsPage } from "./pages/ReviewsPage";
import SettingsPage from "./pages/SettingsPage";
import { StationProvider } from "./station";

export default function App() {
  return (
    <BrowserRouter>
      <StationProvider>
        <TraceSessionProvider>
          <div className="app-shell">
            <Routes>
              <Route path="/" element={<HomePage />} />
              <Route path="/events" element={<EventsPage />} />
              <Route path="/events/:eventId" element={<EventDetailPage />} />
              <Route path="/assistant" element={<AssistantPage />} />
              <Route path="/profile" element={<ProfilePage />} />
              <Route path="/reviews" element={<ReviewsPage />} />
              <Route path="/reviews/:reviewId" element={<ReviewDetailPage />} />
              <Route path="/messages" element={<MessagesPage />} />
              <Route path="/settings" element={<SettingsPage />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </div>
        </TraceSessionProvider>
      </StationProvider>
    </BrowserRouter>
  );
}
