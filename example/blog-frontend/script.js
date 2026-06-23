/**
 * 博客前端交互脚本
 * 功能：
 *   - 点击文章展开/收起详情
 *   - 导航栏滚动效果
 *   - 回到顶部
 *   - 滚动触发的渐入动画
 *   - 搜索文章过滤
 *   - 深色/浅色主题切换
 *   - 阅读进度指示器
 */

document.addEventListener('DOMContentLoaded', () => {
  initArticleExpand();
  initNavScroll();
  initBackToTop();
  initScrollReveal();
  initSearch();
  initThemeToggle();
  initReadingProgress();
  initMobileMenu();
});

/* ══════════════════════════════════════
   1. 点击文章展开/收起详情
   ══════════════════════════════════════ */
function initArticleExpand() {
  const articles = document.querySelectorAll('.blog-post');

  articles.forEach((article, index) => {
    // 给每个文章添加展开按钮（如果没有的话）
    let expandBtn = article.querySelector('.expand-btn');
    if (!expandBtn) {
      expandBtn = document.createElement('button');
      expandBtn.className = 'expand-btn';
      expandBtn.innerHTML = `
        <span class="expand-text">展开阅读全文</span>
        <span class="expand-icon">▼</span>
      `;
      article.appendChild(expandBtn);
    }

    // 获取文章详情区域
    const detail = article.querySelector('.post-detail');
    if (!detail) {
      // 如果 HTML 没有 .post-detail，自动从 .post-excerpt 之后的内容提取
      const excerpt = article.querySelector('.post-excerpt');
      const existingContent = article.querySelector('.post-content');
      if (existingContent) {
        // 把超出 excerpt 的内容作为 detail
        const detailDiv = document.createElement('div');
        detailDiv.className = 'post-detail';
        const contentNodes = [];
        let afterExcerpt = false;
        for (const child of article.children) {
          if (child === expandBtn) continue;
          if (child.classList.contains('post-excerpt')) {
            afterExcerpt = true;
            continue;
          }
          if (afterExcerpt && child !== detailDiv) {
            contentNodes.push(child);
          }
        }
        contentNodes.forEach(node => detailDiv.appendChild(node));
        article.insertBefore(detailDiv, expandBtn);
      }
    }

    // 设置展开状态：默认收起
    const detailEl = article.querySelector('.post-detail');
    if (detailEl) {
      detailEl.classList.add('collapsed');
    }

    // 点击展开按钮切换
    expandBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleArticle(article, expandBtn);
    });

    // 点击文章标题区域也可以展开
    const title = article.querySelector('.post-title, h2, h3');
    if (title) {
      title.style.cursor = 'pointer';
      title.addEventListener('click', () => {
        toggleArticle(article, expandBtn);
      });
    }

    // 如果文章有 data-expanded="true" 属性，初始展开
    if (article.dataset.expanded === 'true') {
      toggleArticle(article, expandBtn, true);
    }
  });
}

function toggleArticle(article, btn, forceExpand) {
  const detail = article.querySelector('.post-detail');
  const icon = btn.querySelector('.expand-icon');
  const text = btn.querySelector('.expand-text');

  if (!detail) return;

  const isExpanded = forceExpand !== undefined
    ? forceExpand
    : detail.classList.contains('collapsed');

  if (isExpanded) {
    // 展开
    detail.classList.remove('collapsed');
    detail.classList.add('expanding');
    article.classList.add('expanded');

    // 高度动画
    detail.style.maxHeight = detail.scrollHeight + 'px';
    setTimeout(() => {
      detail.style.maxHeight = 'none';
      detail.classList.remove('expanding');
      detail.classList.add('expanded');
    }, 400);

    if (icon) icon.textContent = '▲';
    if (text) text.textContent = '收起';
    if (btn) btn.setAttribute('aria-expanded', 'true');

    // 触发自定义事件，便于其他模块联动
    article.dispatchEvent(new CustomEvent('article:expand', { bubbles: true }));
  } else {
    // 收起
    detail.style.maxHeight = detail.scrollHeight + 'px';
    requestAnimationFrame(() => {
      detail.classList.remove('expanded');
      detail.classList.add('collapsed');
      article.classList.remove('expanded');
      detail.style.maxHeight = '0px';
    });

    if (icon) icon.textContent = '▼';
    if (text) text.textContent = '展开阅读全文';
    if (btn) btn.setAttribute('aria-expanded', 'false');

    article.dispatchEvent(new CustomEvent('article:collapse', { bubbles: true }));
  }
}

/* ══════════════════════════════════════
   2. 导航栏滚动效果
   ══════════════════════════════════════ */
function initNavScroll() {
  const nav = document.querySelector('.blog-nav');
  if (!nav) return;

  const updateNav = () => {
    if (window.scrollY > 60) {
      nav.classList.add('nav-scrolled');
    } else {
      nav.classList.remove('nav-scrolled');
    }
  };

  window.addEventListener('scroll', updateNav, { passive: true });
  updateNav();
}

/* ══════════════════════════════════════
   3. 回到顶部按钮
   ══════════════════════════════════════ */
function initBackToTop() {
  const btn = document.querySelector('.back-to-top');
  if (btn) {
    btn.addEventListener('click', () => {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  }

  // 滚动时显示/隐藏回到顶部按钮
  const showThreshold = 500;
  const target = btn || document.querySelector('.back-to-top');

  window.addEventListener('scroll', () => {
    if (!target) return;
    if (window.scrollY > showThreshold) {
      target.classList.add('visible');
    } else {
      target.classList.remove('visible');
    }
  }, { passive: true });
}

/* ══════════════════════════════════════
   4. 滚动触发渐入动画 (Intersection Observer)
   ══════════════════════════════════════ */
function initScrollReveal() {
  const observerOptions = {
    root: null,
    rootMargin: '0px 0px -80px 0px',
    threshold: 0.1
  };

  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        // 支持 stagger 延迟（基于 data-delay 或同级索引）
        const delay = parseInt(entry.target.dataset.delay) || 0;
        setTimeout(() => {
          entry.target.classList.add('reveal-visible');
        }, delay);
        observer.unobserve(entry.target);
      }
    });
  }, observerOptions);

  // 观察需要动画的元素
  document.querySelectorAll(
    '.blog-post, .section-title, .tag-item, .post-card, .profile-card'
  ).forEach((el) => {
    el.classList.add('reveal-hidden');
    observer.observe(el);
  });
}

/* ══════════════════════════════════════
   5. 搜索文章过滤
   ══════════════════════════════════════ */
function initSearch() {
  const searchInput = document.querySelector('.search-input, #search');
  if (!searchInput) return;

  const posts = document.querySelectorAll('.blog-post, .post-card');
  const noResult = document.querySelector('.no-result') || (() => {
    const el = document.createElement('div');
    el.className = 'no-result';
    el.textContent = '😕 没有找到匹配的文章';
    el.style.display = 'none';
    const container = document.querySelector('.posts-grid, .blog-list, main');
    if (container) container.appendChild(el);
    return el;
  })();

  let searchTimeout;

  searchInput.addEventListener('input', () => {
    clearTimeout(searchTimeout);
    searchTimeout = setTimeout(() => {
      const query = searchInput.value.trim().toLowerCase();
      let visibleCount = 0;

      posts.forEach((post) => {
        const title = post.querySelector('.post-title, h2, h3');
        const excerpt = post.querySelector('.post-excerpt, p');
        const tags = post.querySelector('.post-tags, .tags');

        const titleText = title ? title.textContent.toLowerCase() : '';
        const excerptText = excerpt ? excerpt.textContent.toLowerCase() : '';
        const tagsText = tags ? tags.textContent.toLowerCase() : '';

        const match = !query ||
          titleText.includes(query) ||
          excerptText.includes(query) ||
          tagsText.includes(query);

        post.style.display = match ? '' : 'none';
        if (match) visibleCount++;
      });

      if (noResult) {
        noResult.style.display = query && visibleCount === 0 ? 'block' : 'none';
      }
    }, 250);
  });

  // 如果有搜索参数 ?q=xxx 从 URL 读取
  const urlParams = new URLSearchParams(window.location.search);
  const searchQ = urlParams.get('q');
  if (searchQ) {
    searchInput.value = searchQ;
    searchInput.dispatchEvent(new Event('input'));
  }
}

/* ══════════════════════════════════════
   6. 深色/浅色主题切换
   ══════════════════════════════════════ */
function initThemeToggle() {
  const toggleBtn = document.querySelector('.theme-toggle');
  if (!toggleBtn) return;

  // 从 localStorage 读取主题偏好
  const savedTheme = localStorage.getItem('blog-theme') || 'dark';
  applyTheme(savedTheme);

  toggleBtn.addEventListener('click', () => {
    const current = document.documentElement.getAttribute('data-theme') || 'dark';
    const next = current === 'dark' ? 'light' : 'dark';
    applyTheme(next);
    localStorage.setItem('blog-theme', next);
  });
}

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);

  const toggleBtn = document.querySelector('.theme-toggle');
  if (toggleBtn) {
    toggleBtn.textContent = theme === 'dark' ? '☀️' : '🌙';
    toggleBtn.setAttribute('aria-label', `切换到${theme === 'dark' ? '浅色' : '深色'}主题`);
  }
}

/* ══════════════════════════════════════
   7. 阅读进度指示器
   ══════════════════════════════════════ */
function initReadingProgress() {
  const progressBar = document.querySelector('.reading-progress');
  if (!progressBar) return;

  window.addEventListener('scroll', () => {
    const scrollTop = window.scrollY;
    const docHeight = document.documentElement.scrollHeight - window.innerHeight;
    const progress = docHeight > 0 ? (scrollTop / docHeight) * 100 : 0;
    progressBar.style.width = Math.min(progress, 100) + '%';
  }, { passive: true });
}

/* ══════════════════════════════════════
   8. 移动端菜单
   ══════════════════════════════════════ */
function initMobileMenu() {
  const hamburger = document.querySelector('.hamburger, .menu-toggle');
  const navLinks = document.querySelector('.nav-links, .blog-nav-links');

  if (!hamburger || !navLinks) return;

  hamburger.addEventListener('click', () => {
    const isOpen = navLinks.classList.toggle('open');
    hamburger.setAttribute('aria-expanded', isOpen);
    hamburger.innerHTML = isOpen ? '✕' : '☰';
  });

  // 点击导航链接后自动关闭菜单
  navLinks.querySelectorAll('a').forEach((link) => {
    link.addEventListener('click', () => {
      navLinks.classList.remove('open');
      hamburger.setAttribute('aria-expanded', 'false');
      hamburger.innerHTML = '☰';
    });
  });
}

/* ══════════════════════════════════════
   辅助：点击外部收起展开的文章
   ══════════════════════════════════════ */
document.addEventListener('click', (e) => {
  // 如果点击的不是文章内部，收起所有展开的文章
  const expandedArticles = document.querySelectorAll('.blog-post.expanded');
  if (expandedArticles.length === 0) return;

  const isInsideArticle = e.target.closest('.blog-post');
  if (!isInsideArticle) {
    expandedArticles.forEach((article) => {
      const btn = article.querySelector('.expand-btn');
      if (btn) {
        toggleArticle(article, btn, false);
      }
    });
  }
});
