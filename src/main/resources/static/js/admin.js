// 权限管理页面JavaScript

let currentPage = 0;
let pageSize = 10;
let totalPages = 1;
let totalElements = 0;
let allRoles = [];

// 页面加载时初始化
document.addEventListener('DOMContentLoaded', function() {
    // 仅在用户管理页面加载角色与用户列表
    const roleFilterEl = document.getElementById('roleFilter');
    if (roleFilterEl) {
        loadRoles();
        loadUsers();
    }
    
    // 加载角色管理页面
    const rolesContent = document.getElementById('rolesContent');
    if (rolesContent) {
        loadRolesForManagement();
    }
    
    // 加载权限列表页面
    const permissionsContent = document.getElementById('permissionsContent');
    if (permissionsContent) {
        loadPermissionsForList();
    }
    
    // 登出按钮（如果不在onclick中处理）
    const logoutBtn = document.querySelector('.logout-btn');
    if (logoutBtn && !logoutBtn.onclick) {
        logoutBtn.addEventListener('click', function() {
            logout();
        });
    }
});

// 登出函数
async function logout() {
    if (!confirm('确定要登出吗？')) {
        return;
    }
    try {
        const resp = await fetch('/api/auth/logout', { method: 'POST' });
        // 无论成功或失败都回到登录页
        window.location.href = '/login';
    } catch (e) {
        window.location.href = '/login';
    }
}

function handleUnauthorized(response) {
    if (!response) return false;
    if (response.status === 401) {
        alert('登录状态已失效，已为您跳转登录页');
        window.location.href = '/login';
        return true;
    }
    if (response.status === 403) {
        alert('当前账号无访问权限');
        return true;
    }
    return false;
}

// 加载角色列表
async function loadRoles() {
    try {
        const response = await fetch('/admin/roles/api');
        if (!response.ok) {
            console.error('加载角色失败:', response.status, response.statusText);
            return;
        }
        
        const roles = await response.json();
        allRoles = roles;
        
        // 填充角色筛选下拉框（如果存在）
        const roleFilter = document.getElementById('roleFilter');
        if (roleFilter) {
            // 清空现有选项（除了"全部角色"）
            while (roleFilter.children.length > 1) {
                roleFilter.removeChild(roleFilter.lastChild);
            }
            
            roles.forEach(role => {
                const option = document.createElement('option');
                option.value = role.code;
                option.textContent = role.name;
                roleFilter.appendChild(option);
            });
        }
    } catch (error) {
        console.error('加载角色失败:', error);
    }
}

// 加载用户列表
async function loadUsers() {
    try {
        const searchInput = document.getElementById('searchInput');
        const statusFilter = document.getElementById('statusFilter');
        const roleFilter = document.getElementById('roleFilter');
        
        // 检查元素是否存在（只在用户管理页面存在）
        if (!searchInput || !statusFilter || !roleFilter) {
            return;
        }
        
        const keyword = searchInput.value;
        const status = statusFilter.value;
        const roleCode = roleFilter.value;
        
        const params = new URLSearchParams({
            page: currentPage,
            size: pageSize
        });
        
        if (keyword) params.append('keyword', keyword);
        if (status !== '') params.append('status', status);
        if (roleCode) params.append('roleCode', roleCode);
        
        const response = await fetch(`/admin/users/api?${params}`);
        if (!response.ok) {
            if (handleUnauthorized(response)) return;
            let errorMessage = '加载用户失败，请稍后重试';
            try {
                const error = await response.json();
                errorMessage = error.message || error.error || errorMessage;
            } catch (e) {
                console.error('解析错误响应失败:', e);
            }
            alert(errorMessage);
            return;
        }
        
        const result = await response.json();
        
        totalPages = result.totalPages;
        totalElements = result.totalElements;
        
        renderUserTable(result.content);
        updatePagination();
    } catch (error) {
        console.error('加载用户失败:', error);
        alert('加载用户失败，请稍后重试');
    }
}

// 渲染用户表格
function renderUserTable(users) {
    const tbody = document.getElementById('userTableBody');
    if (!tbody) {
        return; // 不在用户管理页面，直接返回
    }
    
    tbody.innerHTML = '';
    
    if (users.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align: center; color: #a0a0a0;">暂无数据</td></tr>';
        return;
    }
    
    users.forEach(user => {
        const tr = document.createElement('tr');
        
        // 用户名
        const usernameTd = document.createElement('td');
        usernameTd.textContent = user.username;
        tr.appendChild(usernameTd);
        
        // 邮箱
        const emailTd = document.createElement('td');
        emailTd.textContent = user.email;
        tr.appendChild(emailTd);
        
        // 真实姓名
        const realNameTd = document.createElement('td');
        realNameTd.textContent = user.realName || '-';
        tr.appendChild(realNameTd);
        
        // 角色
        const roleTd = document.createElement('td');
        if (user.roles && user.roles.length > 0) {
            user.roles.forEach(roleCode => {
                const tag = document.createElement('span');
                tag.className = 'role-tag ' + roleCode.toLowerCase();
                tag.textContent = getRoleName(roleCode);
                roleTd.appendChild(tag);
            });
        } else {
            roleTd.textContent = '-';
        }
        tr.appendChild(roleTd);
        
        // 状态
        const statusTd = document.createElement('td');
        const statusTag = document.createElement('span');
        if (user.status === 0) {
            statusTag.className = 'status-tag active';
            statusTag.textContent = '激活';
        } else if (user.status === 1) {
            statusTag.className = 'status-tag inactive';
            statusTag.textContent = '未激活';
        } else {
            statusTag.className = 'status-tag locked';
            statusTag.textContent = '锁定';
        }
        statusTd.appendChild(statusTag);
        tr.appendChild(statusTd);
        
        // 注册时间
        const createdAtTd = document.createElement('td');
        if (user.createdAt) {
            createdAtTd.textContent = formatDateTime(user.createdAt);
        } else {
            createdAtTd.textContent = '-';
        }
        tr.appendChild(createdAtTd);
        
        // 操作
        const actionTd = document.createElement('td');
        const actionDiv = document.createElement('div');
        actionDiv.className = 'action-buttons';
        
        const editBtn = document.createElement('button');
        editBtn.className = 'action-btn edit-btn';
        editBtn.textContent = '编辑';
        editBtn.onclick = () => editUser(user.id);
        actionDiv.appendChild(editBtn);
        
        const deleteBtn = document.createElement('button');
        deleteBtn.className = 'action-btn delete-btn';
        deleteBtn.textContent = '删除';
        deleteBtn.onclick = () => deleteUser(user.id, user.username);
        actionDiv.appendChild(deleteBtn);
        
        actionTd.appendChild(actionDiv);
        tr.appendChild(actionTd);
        
        tbody.appendChild(tr);
    });
}

// 获取角色名称
function getRoleName(roleCode) {
    const role = allRoles.find(r => r.code === roleCode);
    return role ? role.name : roleCode;
}

// 格式化日期时间
function formatDateTime(dateTimeStr) {
    if (!dateTimeStr) return '-';
    const date = new Date(dateTimeStr);
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    const seconds = String(date.getSeconds()).padStart(2, '0');
    return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`;
}

// 更新分页信息
function updatePagination() {
    const pageInfo = document.getElementById('pageInfo');
    if (!pageInfo) {
        return; // 不在用户管理页面，直接返回
    }
    
    pageInfo.textContent = `第${currentPage + 1}/${totalPages}页 (共${totalElements}条)`;
    
    const prevBtn = document.getElementById('prevBtn');
    const nextBtn = document.getElementById('nextBtn');
    if (prevBtn) prevBtn.disabled = currentPage === 0;
    if (nextBtn) nextBtn.disabled = currentPage >= totalPages - 1;
}

// 切换页码
function changePage(delta) {
    const newPage = currentPage + delta;
    if (newPage >= 0 && newPage < totalPages) {
        currentPage = newPage;
        loadUsers();
    }
}

// 搜索处理
function handleSearch(event) {
    const searchInput = document.getElementById('searchInput');
    if (!searchInput) {
        return; // 不在用户管理页面，直接返回
    }
    
    if (event.key === 'Enter') {
        currentPage = 0;
        loadUsers();
    }
}

// 重置筛选
function resetFilters() {
    const searchInput = document.getElementById('searchInput');
    const statusFilter = document.getElementById('statusFilter');
    const roleFilter = document.getElementById('roleFilter');
    
    if (!searchInput || !statusFilter || !roleFilter) {
        return; // 不在用户管理页面，直接返回
    }
    
    searchInput.value = '';
    statusFilter.value = '';
    roleFilter.value = '';
    currentPage = 0;
    loadUsers();
}

// 显示添加用户模态框
function showAddUserModal() {
    document.getElementById('modalTitle').textContent = '添加用户';
    document.getElementById('userForm').reset();
    document.getElementById('userId').value = '';
    document.getElementById('passwordGroup').style.display = 'block';
    document.getElementById('password').required = true;
    
    renderRoleCheckboxes();
    document.getElementById('userModal').style.display = 'flex';
}

// 编辑用户
async function editUser(userId) {
    try {
        const response = await fetch(`/admin/users/api/${userId}`);
        if (!response.ok) {
            if (handleUnauthorized(response)) return;
            const error = await response.json();
            alert(error.message || error.error || '加载用户信息失败，请稍后重试');
            return;
        }
        
        const user = await response.json();
        
        document.getElementById('modalTitle').textContent = '编辑用户';
        document.getElementById('userId').value = user.id;
        document.getElementById('username').value = user.username;
        document.getElementById('username').disabled = true;
        document.getElementById('email').value = user.email;
        document.getElementById('realName').value = user.realName || '';
        document.getElementById('status').value = user.status;
        document.getElementById('passwordGroup').style.display = 'none';
        document.getElementById('password').required = false;
        
        renderRoleCheckboxes(user.roles || []);
        document.getElementById('userModal').style.display = 'flex';
    } catch (error) {
        console.error('加载用户失败:', error);
        alert('加载用户信息失败，请稍后重试');
    }
}

// 渲染角色复选框
function renderRoleCheckboxes(selectedRoles = []) {
    const container = document.getElementById('roleCheckboxes');
    container.innerHTML = '';
    
    allRoles.forEach(role => {
        const div = document.createElement('div');
        div.className = 'role-checkbox';
        
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.id = `role_${role.code}`;
        checkbox.value = role.code;
        checkbox.checked = selectedRoles.includes(role.code);
        
        const label = document.createElement('label');
        label.htmlFor = `role_${role.code}`;
        label.textContent = role.name;
        
        div.appendChild(checkbox);
        div.appendChild(label);
        container.appendChild(div);
    });
}

// 保存用户
async function saveUser(event) {
    event.preventDefault();
    
    const userId = document.getElementById('userId').value;
    const formData = {
        username: document.getElementById('username').value,
        email: document.getElementById('email').value,
        realName: document.getElementById('realName').value,
        status: parseInt(document.getElementById('status').value),
        roleCodes: getSelectedRoles()
    };
    
    // 如果是添加用户，需要密码
    if (!userId) {
        formData.password = document.getElementById('password').value;
    }
    
    try {
        let response;
        if (userId) {
            // 更新用户
            response = await fetch(`/admin/users/api/${userId}`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(formData)
            });
        } else {
            // 创建用户
            response = await fetch('/admin/users/api', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(formData)
            });
        }
        
        if (response.ok) {
            closeUserModal();
            loadUsers();
            alert(userId ? '用户更新成功' : '用户创建成功');
        } else {
            let errorMessage = '操作失败，请稍后重试';
            try {
                const error = await response.json();
                if (error.errors) {
                    // 验证错误，显示所有错误字段
                    const errorFields = Object.entries(error.errors)
                        .map(([field, msg]) => `${field}: ${msg}`)
                        .join('\n');
                    errorMessage = `验证失败：\n${errorFields}`;
                } else {
                    errorMessage = error.message || error.error || errorMessage;
                }
            } catch (e) {
                console.error('解析错误响应失败:', e);
            }
            alert(errorMessage);
        }
    } catch (error) {
        console.error('保存用户失败:', error);
        alert('保存用户失败，请稍后重试');
    }
}

// 获取选中的角色
function getSelectedRoles() {
    const checkboxes = document.querySelectorAll('#roleCheckboxes input[type="checkbox"]:checked');
    return Array.from(checkboxes).map(cb => cb.value);
}

// 删除用户
async function deleteUser(userId, username) {
    if (!confirm(`确定要删除用户 "${username}" 吗？此操作不可恢复！`)) {
        return;
    }
    
    try {
        const response = await fetch(`/admin/users/api/${userId}`, {
            method: 'DELETE'
        });
        
        if (response.ok) {
            loadUsers();
            alert('用户删除成功');
        } else {
            if (handleUnauthorized(response)) return;
            let errorMessage = '删除失败，请稍后重试';
            try {
                const error = await response.json();
                errorMessage = error.message || error.error || errorMessage;
            } catch (e) {
                console.error('解析错误响应失败:', e);
            }
            alert(errorMessage);
        }
    } catch (error) {
        console.error('删除用户失败:', error);
        alert('删除用户失败，请稍后重试');
    }
}

// 关闭模态框
function closeUserModal() {
    document.getElementById('userModal').style.display = 'none';
    document.getElementById('userForm').reset();
    document.getElementById('username').disabled = false;
}

// 移除点击模态框外部自动关闭的功能
// 现在只能通过点击关闭、取消按钮来关闭模态框

// 加载角色管理页面
async function loadRolesForManagement() {
    try {
        const response = await fetch('/admin/roles/api/with-permissions');
        if (!response.ok) {
            if (handleUnauthorized(response)) return;
            let errorMessage = '加载角色失败，请稍后重试';
            try {
                const error = await response.json();
                errorMessage = error.message || error.error || errorMessage;
            } catch (e) {
                console.error('解析错误响应失败:', e);
            }
            alert(errorMessage);
            return;
        }
        
        const roles = await response.json();
        renderRoleCards(roles);
    } catch (error) {
        console.error('加载角色失败:', error);
        alert('加载角色失败，请稍后重试');
    }
}

// 渲染角色卡片
function renderRoleCards(roles) {
    const rolesContent = document.getElementById('rolesContent');
    if (!rolesContent) return;
    
    rolesContent.innerHTML = roles.map(role => {
        const permissionTags = role.permissions.map(perm => 
            `<span class="permission-tag">${perm}</span>`
        ).join('');
        
        return `
            <div class="role-card">
                <div class="role-card-header">
                    <h3 class="role-name">${role.name}</h3>
                    <span class="role-code">${role.code}</span>
                </div>
                <p class="role-description">${role.description || ''}</p>
                <div class="role-permissions">
                    ${permissionTags}
                </div>
                <div class="role-card-actions">
                    <button class="role-edit-btn" onclick="editRole(${role.id})">
                        <i class="fas fa-edit"></i> 编辑
                    </button>
                    <button class="role-assign-btn" onclick="assignPermissions(${role.id})">
                        <i class="fas fa-key"></i> 分配权限
                    </button>
                    <button class="role-delete-btn" onclick="deleteRole(${role.id}, '${role.name.replace(/'/g, "\\'")}')">
                        <i class="fas fa-trash"></i> 删除
                    </button>
                </div>
            </div>
        `;
    }).join('');
}

// 加载权限列表页面
async function loadPermissionsForList() {
    try {
        const response = await fetch('/admin/permissions/api/dto');
        if (!response.ok) {
            if (handleUnauthorized(response)) return;
            let errorMessage = '加载权限失败，请稍后重试';
            try {
                const error = await response.json();
                errorMessage = error.message || error.error || errorMessage;
            } catch (e) {
                console.error('解析错误响应失败:', e);
            }
            alert(errorMessage);
            return;
        }
        
        const permissions = await response.json();
        renderPermissionCards(permissions);
    } catch (error) {
        console.error('加载权限失败:', error);
        alert('加载权限失败，请稍后重试');
    }
}

// 渲染权限卡片
function renderPermissionCards(permissions) {
    const permissionsContent = document.getElementById('permissionsContent');
    if (!permissionsContent) return;
    
    permissionsContent.innerHTML = permissions.map(permission => {
        return `
            <div class="permission-card">
                <h3 class="permission-name">${permission.name}</h3>
                <div class="permission-code">${permission.code}</div>
                <div class="permission-info">
                    <div class="permission-resource">资源: ${permission.resource}</div>
                    <div class="permission-action">操作: ${permission.action}</div>
                </div>
                <p class="permission-description">${permission.description || ''}</p>
            </div>
        `;
    }).join('');
}

// 显示添加角色模态框
function showAddRoleModal() {
    document.getElementById('roleModalTitle').textContent = '添加角色';
    document.getElementById('roleId').value = '';
    document.getElementById('roleCode').value = '';
    document.getElementById('roleCode').disabled = false;
    document.getElementById('roleName').value = '';
    document.getElementById('roleDescription').value = '';
    document.getElementById('roleStatus').value = '0';
    document.getElementById('roleModal').style.display = 'flex';
}

// 编辑角色
async function editRole(roleId) {
    try {
        const response = await fetch(`/admin/roles/api/${roleId}`);
        if (!response.ok) {
            if (handleUnauthorized(response)) return;
            let errorMessage = '加载角色信息失败，请稍后重试';
            try {
                const error = await response.json();
                errorMessage = error.message || error.error || errorMessage;
            } catch (e) {
                console.error('解析错误响应失败:', e);
            }
            alert(errorMessage);
            return;
        }
        
        const role = await response.json();
        
        document.getElementById('roleModalTitle').textContent = '编辑角色';
        document.getElementById('roleId').value = role.id;
        document.getElementById('roleCode').value = role.code;
        document.getElementById('roleCode').disabled = true;
        document.getElementById('roleName').value = role.name;
        document.getElementById('roleDescription').value = role.description || '';
        document.getElementById('roleStatus').value = role.status;
        document.getElementById('roleModal').style.display = 'flex';
    } catch (error) {
        console.error('加载角色失败:', error);
        alert('加载角色信息失败，请稍后重试');
    }
}

// 关闭角色模态框
function closeRoleModal() {
    document.getElementById('roleModal').style.display = 'none';
    document.getElementById('roleForm').reset();
    document.getElementById('roleCode').disabled = false;
}

// 保存角色
async function saveRole(event) {
    event.preventDefault();
    
    const roleId = document.getElementById('roleId').value;
    const formData = {
        code: document.getElementById('roleCode').value,
        name: document.getElementById('roleName').value,
        description: document.getElementById('roleDescription').value,
        status: parseInt(document.getElementById('roleStatus').value)
    };
    
    try {
        let response;
        if (roleId) {
            // 更新角色（编辑时不更新code）
            const updateData = {
                name: formData.name,
                description: formData.description,
                status: formData.status
            };
            response = await fetch(`/admin/roles/api/${roleId}`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(updateData)
            });
        } else {
            // 创建角色
            response = await fetch('/admin/roles/api', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(formData)
            });
        }
        
        if (response.ok) {
            closeRoleModal();
            loadRolesForManagement();
            alert(roleId ? '角色更新成功' : '角色创建成功');
        } else {
            if (handleUnauthorized(response)) return;
            let errorMessage = '操作失败，请稍后重试';
            try {
                const error = await response.json();
                if (error.errors) {
                    const errorFields = Object.entries(error.errors)
                        .map(([field, msg]) => `${field}: ${msg}`)
                        .join('\n');
                    errorMessage = `验证失败：\n${errorFields}`;
                } else {
                    errorMessage = error.message || error.error || errorMessage;
                }
            } catch (e) {
                console.error('解析错误响应失败:', e);
            }
            alert(errorMessage);
        }
    } catch (error) {
        console.error('保存角色失败:', error);
        alert('保存角色失败，请稍后重试');
    }
}

// 分配权限
let currentRoleIdForPermission = null;

async function assignPermissions(roleId) {
    currentRoleIdForPermission = roleId;
    
    try {
        // 加载所有权限
        const permissionsResponse = await fetch('/admin/permissions/api');
        if (!permissionsResponse.ok) {
            alert('加载权限列表失败，请稍后重试');
            return;
        }
        const allPermissions = await permissionsResponse.json();
        
        // 加载当前角色的权限
        const roleResponse = await fetch(`/admin/roles/api/${roleId}`);
        if (!roleResponse.ok) {
            if (handleUnauthorized(roleResponse)) return;
            alert('加载角色信息失败，请稍后重试');
            return;
        }
        const role = await roleResponse.json();
        const selectedPermissionCodes = role.permissions || [];
        
        // 渲染权限复选框
        const container = document.getElementById('permissionCheckboxes');
        container.innerHTML = '';
        
        allPermissions.forEach(permission => {
            const div = document.createElement('div');
            div.className = 'form-group';
            
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.id = `perm_${permission.id}`;
            checkbox.value = permission.code;
            checkbox.checked = selectedPermissionCodes.includes(permission.code);
            
            const label = document.createElement('label');
            label.htmlFor = `perm_${permission.id}`;
            label.textContent = `${permission.name} (${permission.code})`;
            
            div.appendChild(checkbox);
            div.appendChild(label);
            container.appendChild(div);
        });
        
        document.getElementById('permissionModalTitle').textContent = `为角色"${role.name}"分配权限`;
        document.getElementById('permissionModal').style.display = 'flex';
    } catch (error) {
        console.error('加载权限失败:', error);
        alert('加载权限列表失败，请稍后重试');
    }
}

// 关闭权限模态框
function closePermissionModal() {
    document.getElementById('permissionModal').style.display = 'none';
    currentRoleIdForPermission = null;
}

// 保存权限分配
async function savePermissions() {
    if (!currentRoleIdForPermission) {
        alert('角色ID不存在');
        return;
    }
    
    const checkboxes = document.querySelectorAll('#permissionCheckboxes input[type="checkbox"]:checked');
    const permissionCodes = Array.from(checkboxes).map(cb => cb.value);
    
    try {
        const response = await fetch(`/admin/roles/api/${currentRoleIdForPermission}/permissions`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(permissionCodes)
        });
        
        if (response.ok) {
            closePermissionModal();
            loadRolesForManagement();
            alert('权限分配成功');
        } else {
            if (handleUnauthorized(response)) return;
            let errorMessage = '权限分配失败，请稍后重试';
            try {
                const error = await response.json();
                errorMessage = error.message || error.error || errorMessage;
            } catch (e) {
                console.error('解析错误响应失败:', e);
            }
            alert(errorMessage);
        }
    } catch (error) {
        console.error('保存权限失败:', error);
        alert('保存权限失败，请稍后重试');
    }
}

// 删除角色
async function deleteRole(roleId, roleName) {
    if (!confirm(`确定要删除角色 "${roleName}" 吗？此操作不可恢复！`)) {
        return;
    }
    
    try {
        const response = await fetch(`/admin/roles/api/${roleId}`, {
            method: 'DELETE'
        });
        
        if (response.ok) {
            loadRolesForManagement();
            alert('角色删除成功');
        } else {
            if (handleUnauthorized(response)) return;
            let errorMessage = '删除失败，请稍后重试';
            try {
                const error = await response.json();
                errorMessage = error.message || error.error || errorMessage;
            } catch (e) {
                console.error('解析错误响应失败:', e);
            }
            alert(errorMessage);
        }
    } catch (error) {
        console.error('删除角色失败:', error);
        alert('删除角色失败，请稍后重试');
    }
}
