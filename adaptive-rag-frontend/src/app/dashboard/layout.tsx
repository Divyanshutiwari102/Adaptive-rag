import { AuthGuard } from '@/components/auth/AuthGuard';
import { DashboardLayout } from '@/components/layout/DashboardLayout';

export const metadata = {
  title: { template: '%s | AdaptiveRAG', default: 'Dashboard | AdaptiveRAG' },
};

export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <AuthGuard>
      <DashboardLayout>{children}</DashboardLayout>
    </AuthGuard>
  );
}
