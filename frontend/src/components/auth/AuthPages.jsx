// One route chunk for the signed-out and forced-change pages. `App.jsx` lazily imports this module
// five times (one `lazy()` per page); the bundler emits a single chunk because they share a module.
export { default as LoginPage } from '../LoginPage.jsx';
export { default as RegisterPage } from '../RegisterPage.jsx';
export { default as ChangePasswordPage } from '../ChangePasswordPage.jsx';
export { default as ForgotPasswordPage } from './ForgotPasswordPage.jsx';
export { default as ResetPasswordPage } from './ResetPasswordPage.jsx';
