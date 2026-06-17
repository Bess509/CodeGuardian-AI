(() => {
  const originalFetch = window.fetch;
  window.fetch = async function(input, init) {
    const resp = await originalFetch(input, init);
    try {
      const cloned = resp.clone();
      const ct = cloned.headers.get('content-type') || '';
      if (resp.status === 401) {
        alert('登录状态已失效，已为您跳转登录页');
        window.location.href = '/login';
      } else if (ct.includes('application/json')) {
        const data = await cloned.json();
        const msg = typeof data?.message === 'string' ? data.message : '';
        if (
          data?.status === 401 &&
          data?.error === '未登录' &&
          msg.indexOf('token 无效') !== -1
        ) {
          alert('登录状态已失效，已为您跳转登录页');
          window.location.href = '/login';
        }
      }
    } catch (e) {}
    return resp;
  };
})();
