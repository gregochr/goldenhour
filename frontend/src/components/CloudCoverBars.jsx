import React from 'react';
import PropTypes from 'prop-types';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts';

const CLOUD_BANDS = [
  { key: 'low', label: 'Low', color: '#94a3b8' },
  { key: 'mid', label: 'Mid', color: '#60a5fa' },
  { key: 'high', label: 'High', color: '#a78bfa' },
];

/**
 * Renders a small bar chart showing low, mid, and high cloud cover percentages.
 *
 * @param {object} props
 * @param {number} props.lowCloud - Low cloud cover percentage (0–100).
 * @param {number} props.midCloud - Mid cloud cover percentage (0–100).
 * @param {number} props.highCloud - High cloud cover percentage (0–100).
 */
export default function CloudCoverBars({ lowCloud = 0, midCloud = 0, highCloud = 0 }) {
  const data = [
    { name: 'Low', value: lowCloud ?? 0 },
    { name: 'Mid', value: midCloud ?? 0 },
    { name: 'High', value: highCloud ?? 0 },
  ];

  return (
    <div data-testid="cloud-cover-bars" className="w-full">
      <p className="text-xs text-gray-500 mb-1">Cloud cover</p>
      <ResponsiveContainer width="100%" height={64}>
        <BarChart data={data} margin={{ top: 0, right: 0, bottom: 0, left: -24 }}>
          <XAxis dataKey="name" tick={{ fontSize: 10, fill: '#9ca3af' }} axisLine={false} tickLine={false} />
          <YAxis domain={[0, 100]} tick={{ fontSize: 10, fill: '#9ca3af' }} axisLine={false} tickLine={false} />
          <Tooltip
            formatter={(value) => [`${value}%`]}
            contentStyle={{ background: '#1f2937', border: '1px solid #374151', borderRadius: '6px', fontSize: '11px', padding: '6px 10px' }}
            labelStyle={{ color: '#9ca3af' }}
            itemStyle={{ color: '#f3f4f6' }}
            cursor={{ fill: 'rgba(255,255,255,0.04)' }}
          />
          <Bar dataKey="value" radius={[2, 2, 0, 0]}>
            {data.map((entry, index) => (
              <Cell key={entry.name} fill={CLOUD_BANDS[index].color} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

CloudCoverBars.propTypes = {
  lowCloud: PropTypes.number,
  midCloud: PropTypes.number,
  highCloud: PropTypes.number,
};

