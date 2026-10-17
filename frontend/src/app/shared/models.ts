export interface UserDto {
  userId: string;
  username: string;
  email: string;
  displayName: string;
  role: string;
}

export interface LoginResponse {
  user: UserDto;
  message: string;
}

export interface AppMessage {
  title: string;
  body: string;
  tone?: 'info' | 'success' | 'error';
  actionLabel?: string;
  details?: Record<string, string>;
  autoDismissMs?: number | null;
}
