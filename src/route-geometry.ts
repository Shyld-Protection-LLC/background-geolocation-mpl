/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
// Shyld: upstream's route geometry from `web.ts`, moved here unchanged (FORK.md).

const EARTH_RADIUS_M = 6371000;

function toRadians(degrees: number): number {
  return (degrees * Math.PI) / 180;
}

export function haversine(point1: [number, number], point2: [number, number]): number {
  const [lon1, lat1] = point1;
  const [lon2, lat2] = point2;
  const dLat = toRadians(lat2 - lat1);
  const dLon = toRadians(lon2 - lon1);

  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);

  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

  return EARTH_RADIUS_M * c;
}

function distancePointToLineSegment(
  point: [number, number],
  lineStart: [number, number],
  lineEnd: [number, number],
): number {
  // Calculate the distances between the three points using Haversine
  const dist_A_B = haversine(point, lineStart);
  const dist_A_C = haversine(point, lineEnd);
  const dist_B_C = haversine(lineStart, lineEnd);

  // Handle the edge case where the line segment is a single point
  if (dist_B_C === 0) {
    return dist_A_B;
  }

  // Check if the angles at the line segment's endpoints are obtuse.
  // We use the Law of Cosines (c^2 = a^2 + b^2 - 2ab*cos(C))
  // If cos(C) < 0, the angle is obtuse.

  // Angle at B (lineStart)
  // Use a small epsilon to handle floating point inaccuracies in division by zero
  const cos_B = (dist_A_B ** 2 + dist_B_C ** 2 - dist_A_C ** 2) / (2 * dist_A_B * dist_B_C + Number.EPSILON);
  if (cos_B < 0) {
    return dist_A_B;
  }

  // Angle at C (lineEnd)
  const cos_C = (dist_A_C ** 2 + dist_B_C ** 2 - dist_A_B ** 2) / (2 * dist_A_C * dist_B_C + Number.EPSILON);
  if (cos_C < 0) {
    return dist_A_C;
  }

  // If both angles are acute, the closest point is on the line segment itself.
  // We can calculate the distance (height of the triangle) using its area.

  // 1. Calculate the semi-perimeter of the triangle ABC
  const s = (dist_A_B + dist_A_C + dist_B_C) / 2;

  // 2. Calculate the area using Heron's formula
  const area = Math.sqrt(Math.max(0, s * (s - dist_A_B) * (s - dist_A_C) * (s - dist_B_C)));

  // 3. The distance is the height of the triangle from point A to the base BC
  // Area = 0.5 * base * height  =>  height = 2 * Area / base
  return (2 * area) / (dist_B_C + Number.EPSILON);
}

export function distancePointToRoute(point: [number, number], plannedRoute: [number, number][]): number {
  // If the route has less than 2 points, we can't form a segment.
  if (plannedRoute.length < 2) {
    const only = plannedRoute[0];
    if (only) {
      return haversine(point, only);
    }
    return Infinity; // No line segments to measure against
  }

  let minDistance = Infinity;

  for (let i = 0; i < plannedRoute.length - 1; i++) {
    const lineStart = plannedRoute[i];
    const lineEnd = plannedRoute[i + 1];
    // Shyld: strict index checks (noUncheckedIndexedAccess).
    if (!lineStart || !lineEnd) continue;
    const distance = distancePointToLineSegment(point, lineStart, lineEnd);
    if (distance < minDistance) {
      minDistance = distance;
    }
  }

  return minDistance;
}
