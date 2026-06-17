// 仪表盘图表初始化

// 图表1: 代码健康分（环形图）
function initHealthScoreChart() {
    const ctx = document.getElementById('healthScoreChart');
    if (!ctx) return;
    
    // 应用高度配置
    if (window.dashboardConfig && window.dashboardConfig.chartHeight) {
        const wrapper = ctx.parentElement;
        if (wrapper && wrapper.classList.contains('chart-wrapper')) {
            wrapper.style.height = window.dashboardConfig.chartHeight + 'px';
        }
    }
    
    // 计算圆环厚度 (cutout = 100% - thickness%)
    let cutout = '70%';
    if (window.dashboardConfig && window.dashboardConfig.ringThickness) {
        const thickness = Math.max(5, Math.min(90, window.dashboardConfig.ringThickness));
        cutout = (100 - thickness) + '%';
    }

    const healthScore = (window.dashboardData && window.dashboardData.healthScore !== undefined) 
        ? window.dashboardData.healthScore 
        : 100;
        
    // 根据分数决定颜色
    let scoreColor = '#10B981'; // 绿色
    if (healthScore < 60) scoreColor = '#EF4444'; // 红色
    else if (healthScore < 80) scoreColor = '#F97316'; // 橙色

    new Chart(ctx, {
        type: 'doughnut',
        data: {
            labels: ['健康', '不健康'],
            datasets: [{
                data: [healthScore, 100 - healthScore],
                backgroundColor: [
                    scoreColor, // 动态颜色
                    '#30363d'  // 灰色背景
                ],
                borderWidth: 0,
                cutout: cutout
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    display: false
                },
                tooltip: {
                    enabled: true,
                    callbacks: {
                        label: function(context) {
                            return context.label + ': ' + context.parsed + '%';
                        }
                    }
                }
            }
        },
        plugins: [{
            id: 'centerText',
            beforeDraw: function(chart) {
                const ctx = chart.ctx;
                const centerX = chart.chartArea.left + (chart.chartArea.right - chart.chartArea.left) / 2;
                const centerY = chart.chartArea.top + (chart.chartArea.bottom - chart.chartArea.top) / 2;
                
                ctx.save();
                // 绘制黑色背景框
                ctx.fillStyle = '#000000';
                ctx.fillRect(centerX - 35, centerY - 15, 70, 30);
                
                // 绘制颜色方块
                ctx.fillStyle = scoreColor;
                ctx.fillRect(centerX - 30, centerY - 10, 12, 12);
                
                // 绘制分数文字
                ctx.fillStyle = '#ffffff';
                ctx.font = 'bold 14px sans-serif';
                ctx.textAlign = 'left';
                ctx.textBaseline = 'middle';
                ctx.fillText(healthScore.toString(), centerX - 15, centerY - 2);
                
                ctx.restore();
            }
        }]
    });
}

// 图表2: 问题分布（垂直柱状图）
function initProblemDistributionChart() {
    const ctx = document.getElementById('problemDistributionChart');
    if (!ctx) return;
    
    const distObj = (window.dashboardData && window.dashboardData.problemDistribution) 
        ? window.dashboardData.problemDistribution 
        : { critical: 0, high: 0, medium: 0, low: 0 };
        
    const dataValues = [
        distObj.critical || 0, 
        distObj.high || 0, 
        distObj.medium || 0, 
        distObj.low || 0
    ];
    
    // 计算最大值以动态设置Y轴
    const maxVal = Math.max(...dataValues, 10); // 至少10

    new Chart(ctx, {
        type: 'bar',
        data: {
            labels: ['严重', '高危', '中危', '低危'],
            datasets: [{
                label: '问题数量',
                data: dataValues,
                backgroundColor: [
                    '#EF4444', // 红色 - 严重
                    '#F97316', // 橙色 - 高危
                    '#EAB308', // 黄色 - 中危
                    '#10B981'  // 绿色 - 低危
                ],
                borderRadius: 8,
                borderSkipped: false
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    display: false
                },
                tooltip: {
                    enabled: true,
                    callbacks: {
                        label: function(context) {
                            return '数量: ' + context.parsed.y;
                        }
                    }
                }
            },
            scales: {
                y: {
                    beginAtZero: true,
                    suggestedMax: maxVal + 2,
                    ticks: {
                        color: '#6b6b6b',
                        stepSize: Math.ceil(maxVal / 5)
                    },
                    grid: {
                        color: '#2d2d2d'
                    }
                },
                x: {
                    ticks: {
                        color: '#6b6b6b'
                    },
                    grid: {
                        display: false
                    }
                }
            }
        }
    });
}

// 图表3: 项目问题统计（堆叠柱状图）
function initProjectStatisticsChart() {
    const ctx = document.getElementById('projectStatisticsChart');
    if (!ctx) return;
    
    const stats = (window.dashboardData && window.dashboardData.projectStats) 
        ? window.dashboardData.projectStats 
        : [];
        
    const labels = stats.map(s => s.projectName);
    const criticalData = stats.map(s => s.criticalCount);
    const highData = stats.map(s => s.highCount);
    const mediumData = stats.map(s => s.mediumCount);
    const lowData = stats.map(s => s.lowCount);
    
    // 计算最大总数用于Y轴
    let maxTotal = 0;
    stats.forEach(s => {
        const total = s.criticalCount + s.highCount + s.mediumCount + s.lowCount;
        if (total > maxTotal) maxTotal = total;
    });
    maxTotal = Math.max(maxTotal, 10);

    new Chart(ctx, {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [
                {
                    label: '严重',
                    data: criticalData,
                    backgroundColor: '#EF4444'
                },
                {
                    label: '高危',
                    data: highData,
                    backgroundColor: '#F97316'
                },
                {
                    label: '中危',
                    data: mediumData,
                    backgroundColor: '#EAB308'
                },
                {
                    label: '低危',
                    data: lowData,
                    backgroundColor: '#10B981'
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    display: true,
                    position: 'top',
                    labels: {
                        color: '#c9d1d9',
                        usePointStyle: true,
                        padding: 12,
                        font: {
                            size: 12
                        },
                        boxWidth: 12,
                        boxHeight: 12
                    }
                },
                tooltip: {
                    enabled: true,
                    callbacks: {
                        label: function(context) {
                            return context.dataset.label + ': ' + context.parsed.y;
                        }
                    }
                }
            },
            scales: {
                x: {
                    stacked: true,
                    ticks: {
                        color: '#8b949e',
                        font: {
                            size: 12
                        },
                        padding: 3
                    },
                    grid: {
                        display: false
                    }
                },
                y: {
                    stacked: true,
                    beginAtZero: true,
                    suggestedMax: maxTotal + 5,
                    min: 0,
                    ticks: {
                        color: '#8b949e',
                        stepSize: Math.ceil(maxTotal / 5),
                        font: {
                            size: 12
                        },
                        padding: 3
                    },
                    grid: {
                        color: '#30363d'
                    }
                }
            },
            layout: {
                padding: {
                    bottom: 0,
                    top: 0,
                    left: 0,
                    right: 0
                }
            }
        }
    });
}

async function logout() {
    if (!confirm('确定要登出吗？')) {
        return;
    }
    try {
        const resp = await fetch('/api/auth/logout', { method: 'POST' });
        window.location.href = '/login';
    } catch (e) {
        window.location.href = '/login';
    }
}

// 页面加载完成后初始化所有图表
document.addEventListener('DOMContentLoaded', function() {
    // 修复：强制移除所有可能导致页面置灰的遮罩层
    const overlays = document.querySelectorAll('.modal-overlay, .modal-backdrop, .overlay, .modal');
    overlays.forEach(el => {
        el.style.display = 'none';
        // 如果是全屏遮罩，直接移除可能更安全，但隐藏通常足够
        if (getComputedStyle(el).position === 'fixed') {
            el.style.setProperty('display', 'none', 'important');
        }
    });

    // 修复：确保主内容区域可以滚动且不被遮挡
    const mainContent = document.querySelector('.main-content');
    if (mainContent) {
        mainContent.style.overflowY = 'auto';
        // 确保 z-index 自动，避免被父级或其他层级上下文限制
        mainContent.style.zIndex = 'auto'; 
    }

    // 设置Chart.js默认颜色
    Chart.defaults.color = '#ffffff';
    Chart.defaults.borderColor = '#2d2d2d';
    
    initHealthScoreChart();
    initProblemDistributionChart();
    initProjectStatisticsChart();
});
