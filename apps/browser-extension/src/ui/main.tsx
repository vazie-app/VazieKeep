import { render } from 'preact';
import { App } from './App';
import { ToastHost } from './components/dialogs';
import { boot } from './state/client';
import { reset, stack } from './state/route';
import './styles/tokens.css';
import './styles/base.css';
import './styles/components.css';
import './styles/screens.css';

function initialRoute(): void {
  const hash = location.hash.replace(/^#/, '');
  if (hash === 'hub') reset({ name: 'hub' });
  else if (hash === 'create') reset({ name: 'create' });
  else if (hash === 'import') stack.set([{ name: 'vault' }, { name: 'import' }]);
  else if (hash === 'vaults') stack.set([{ name: 'vault' }, { name: 'settings', page: 'vaults' }]);
  else if (hash.startsWith('entry='))
    stack.set([{ name: 'vault' }, { name: 'entry', id: hash.slice(6) }]);
  else if (hash === 'welcome') reset({ name: 'vault' });
  history.replaceState(null, '', location.pathname);
}

initialRoute();
const root = document.getElementById('root');
if (root) {
  render(
    <>
      <App />
      <ToastHost />
    </>,
    root,
  );
}
void boot();
